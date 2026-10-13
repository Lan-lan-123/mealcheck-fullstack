import { publishDietChange } from '../utils/dietDataChanges.js'
const API_BASE = import.meta.env?.VITE_API_BASE || ''
const REQUEST_TIMEOUT_MS = 75_000
const LONG_REQUEST_TIMEOUT_MS = 180_000
const LONG_REQUEST_PATHS = new Set([
  '/api/assistant/ask', '/api/meals/analyze', '/api/reports/weekly',
  '/api/admin/vision-evaluation/recognize'
])
export class ApiError extends Error {
  constructor(message, status, code, retryAfter, requestId) {
    super(message)
    this.name = 'ApiError'
    Object.assign(this, { status, code, retryAfter, requestId })
  }
}

const API_ENDPOINTS = {
  assistantAsk: '/api/assistant/ask'
}

export function aiBudget(signal) { return request('/api/profile/ai-budget', { signal }) }
export function adminAiBudget() { return request('/api/admin/ai-budget') }

export function getToken() {
  return localStorage.getItem('mealcheck_token')
}

async function mutateDiet(path, options, kind) { const result = await request(path, options); publishDietChange(kind); return result }

const jsonOptions = (method, body) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export function createManualMeal(body) { return mutateDiet('/api/meals/manual', jsonOptions('POST', body), 'meals') }
export function updateMealMetadata(id, body) { return mutateDiet(`/api/meals/${id}/metadata`, jsonOptions('PUT', body), 'meals') }
export function declareSkippedMeal(body, undo = false) { return mutateDiet('/api/meals/skipped', jsonOptions(undo ? 'DELETE' : 'PUT', body), 'meals') }
export function mealCoverage(from, to, signal) { return request(`/api/meals/coverage?from=${from}&to=${to}`, { signal }) }
export function behaviorGoals({ page = 0, size = 20 } = {}) { return request(`/api/profile/behavior-goals?page=${page}&size=${size}`) }
export function saveBehaviorGoal(body, id) { return mutateDiet(`/api/profile/behavior-goals${id ? `/${id}` : ''}`, jsonOptions(id ? 'PUT' : 'POST', body), 'behavior-goals') }
export function actionPlans({ from, to, page = 0, size = 20 } = {}) { return request(`/api/plans?page=${page}&size=${size}${from && to ? `&from=${from}&to=${to}` : ''}`) }
export function saveActionPlan(body, id) { return mutateDiet(`/api/plans${id ? `/${id}` : ''}`, jsonOptions(id ? 'PUT' : 'POST', body), 'plans') }
export function answerFeedback(id, body) { return request(`/api/assistant/messages/${id}/feedback`, jsonOptions('PUT', body)) }
export function getAnswerFeedback(id) { return request(`/api/assistant/messages/${id}/feedback`) }
export function cancelAssistantRequest(id) { return request(`/api/assistant/requests/${id}`, { method: 'DELETE' }) }
export function weeklyReportHistory(page, signal) { return request(`/api/reports/weekly/history?page=${page}&size=20`, { signal }) }
export function archivedWeeklyReport(id) { return request(`/api/reports/weekly/history/${id}`) }

export async function streamDietAssistant(question, history, conversationId, signal, onEvent) {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  let requestId, timedOut = false
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, LONG_REQUEST_TIMEOUT_MS)
  const cancel = () => { if (requestId) cancelAssistantRequest(requestId).catch(() => {}) }
  controller.signal.addEventListener('abort', cancel, { once: true })
  try {
    const response = await fetch(`${API_BASE}/api/assistant/stream`, { ...jsonOptions('POST', { question, history, conversationId }), headers: authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }), signal: controller.signal })
    requestId = response.headers.get('X-Assistant-Request-ID')
    if (requestId) onEvent('request', { requestId })
    if ([404, 405].includes(response.status)) return askDietAssistant(question, history, conversationId, signal)
    if (!response.ok) {
      let data; try { data = await response.json() } catch { }
      if (response.status === 401) { clearSession(); window.dispatchEvent(new Event('mealcheck:session-expired')) }
      throw new ApiError(data?.message || '助手请求失败', response.status, data?.code, 0, requestId)
    }
    if (!response.headers.get('Content-Type')?.includes('text/event-stream')) return response.json()
    const reader = response.body.getReader(), decoder = new TextDecoder()
    let buffer = '', completed = null, failure = null
    const consume = () => {
      let end
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, end); buffer = buffer.slice(end + 2)
        let event = 'message'; const data = []
        for (const line of block.split('\n')) { if (line.startsWith('event:')) event = line.slice(6).trim(); if (line.startsWith('data:')) data.push(line.slice(5).trimStart()) }
        if (!data.length) continue
        const value = JSON.parse(data.join('\n'))
        if (value.requestId) requestId = value.requestId
        onEvent(event, value)
        if (event === 'complete') completed = value
        if (event === 'error') failure = new ApiError(value.message, 502, 'STREAM_FAILED', 0, value.requestId)
      }
    }
    try {
      while (true) { const { done, value } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); buffer = buffer.replace(/\r\n/g, '\n'); consume() }
      buffer += decoder.decode(); consume()
      if (failure) throw failure
      if (!completed) throw new ApiError('连接中断，回复未完成。请查看会话历史后再重试。', 502, 'STREAM_INTERRUPTED', 0, requestId)
      return completed
    } finally { reader.releaseLock() }
  } catch (err) {
    cancel()
    if (timedOut) throw new ApiError('回复超时，已取消等待。', 408, 'CLIENT_TIMEOUT', 0, requestId)
    throw err
  } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); controller.signal.removeEventListener('abort', cancel) }
}

export function setSession(auth) {
  localStorage.setItem('mealcheck_token', auth.token)
  localStorage.setItem(
    'mealcheck_user',
    JSON.stringify({
      username: auth.username,
      displayName: auth.displayName,
      role: auth.role || 'USER'
    })
  )
}

export function clearSession() {
  localStorage.removeItem('mealcheck_token')
  localStorage.removeItem('mealcheck_user')
}

export function getUser() {
  const raw = localStorage.getItem('mealcheck_user')
  try { return raw ? JSON.parse(raw) : null } catch { clearSession(); return null }
}

function authHeaders(extraHeaders = {}) {
  const headers = { ...extraHeaders }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

async function request(path, options = {}) {
  const headers = authHeaders(options.headers || {})
  const controller = new AbortController()
  const abort = () => controller.abort(options.signal?.reason)
  if (options.signal?.aborted) abort()
  else options.signal?.addEventListener('abort', abort, { once: true })
  let timedOut = false
  const { timeoutMs, ...fetchOptions } = options
  const requestTimeout = timeoutMs ?? (LONG_REQUEST_PATHS.has(path.split('?')[0]) ? LONG_REQUEST_TIMEOUT_MS : REQUEST_TIMEOUT_MS)
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, requestTimeout)
  try {
  const res = await fetch(`${API_BASE}${path}`, { ...fetchOptions, headers, signal: controller.signal })

  const text = await res.text()
  let data = null

  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }

  if (!res.ok) {
    if (res.status === 401 && headers.Authorization && headers.Authorization === `Bearer ${getToken()}` && !path.startsWith('/api/auth/')) {
      clearSession()
      window.dispatchEvent(new Event('mealcheck:session-expired'))
    }
    const retryAfter = Number(res.headers.get('Retry-After')) || 0
    const fallback = res.status === 401 ? '登录已失效，请重新登录。' : res.status === 429 ? '请求过于频繁，请稍后重试。' : '请求失败'
    throw new ApiError((typeof data === 'string' ? data : data?.message || data?.error) || fallback,
      res.status, data?.code, retryAfter, res.headers.get('X-Request-ID'))
  }

  return data
  } catch (error) {
    if (timedOut) throw new ApiError('请求超时，请先查询已有结果或任务状态，确认后再重试。', 408, 'CLIENT_TIMEOUT', 0, null)
    throw error
  } finally {
    clearTimeout(timeout)
    options.signal?.removeEventListener('abort', abort)
  }
}

export function register(payload) {
  return request('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function login(payload) {
  return request('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function captcha() {
  return request('/api/auth/captcha')
}

export function logout() {
  return request('/api/auth/logout', {
    method: 'POST'
  })
}

export function deleteCurrentAccount(password) {
  return request('/api/profile/account', {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password })
  })
}

export function analyzeMeal(file, goal, key, signal) {
  const form = new FormData()
  form.append('image', file)
  form.append('goal', goal)
  return request('/api/meals/analysis-requests', {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    signal,
    body: form
  })
}
export function mealAnalysisRequest(key, signal) {
  return request(`/api/meals/analysis-requests/${encodeURIComponent(key)}`, { signal })
}
export function correctMeal(id, payload) {
  return request(`/api/meals/${id}/corrections`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}

export function listMeals(params = {}, signal) {
  return request(`/api/meals${toQuery(params)}`, { signal })
}

export function weeklyReport(days = 7) {
  return request(`/api/reports/weekly?days=${days}`)
}

export function requestWeeklyReport(days = 7, signal) {
  return request(`/api/reports/weekly/jobs?days=${days}`, { method: 'POST', signal })
}

export function weeklyReportJob(id, signal) {
  return request(`/api/reports/weekly/jobs/${encodeURIComponent(id)}`, { signal })
}

export function latestWeeklyReport() {
  return request('/api/reports/weekly/latest')
}

export function currentGoal() {
  return request('/api/profile/goals/current')
}

export function updateCurrentGoal(payload) {
  return mutateDiet('/api/profile/goals/current', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }, 'diet-goal')
}

export function mealTrends(days = 30) {
  return request(`/api/meals/trends?days=${days}`)
}

export function healthCheck() {
  return request('/api/health')
}

export function deleteMeal(id) {
  return request(`/api/meals/${id}`, {
    method: 'DELETE'
  })
}

export function mealNutrition(id, signal) {
  return request(`/api/meals/${id}/nutrition`, { signal })
}

export function updateMealNutrition(id, payload, signal) {
  return request(`/api/meals/${id}/nutrition`, {
    method: 'PUT', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}

export function askDietAssistant(question, history = [], conversationId = null, signal) {
  return request(API_ENDPOINTS.assistantAsk, {
    method: 'POST',
    signal,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, history, conversationId })
  })
}

export function assistantConversations() {
  return request('/api/assistant/conversations')
}

export function assistantConversation(id, { before, limit = 40, signal } = {}) {
  return request(`/api/assistant/conversations/${id}${toQuery({ before, limit })}`, { signal })
}

export function deleteAssistantConversation(id) {
  return request(`/api/assistant/conversations/${id}`, { method: 'DELETE' })
}

export function assistantProactiveAdvice() {
  return request('/api/assistant/proactive')
}

export function reindexKnowledge() {
  return request('/api/knowledge/reindex', {
    method: 'POST'
  })
}

/**
 * =========================
 * 管理员后台接口
 * =========================
 */

export function adminStats() {
  return request('/api/admin/stats')
}

export function adminDashboard() {
  return request('/api/admin/dashboard')
}

export function adminAuditLogs(params = {}) {
  return request(`/api/admin/audit-logs${toQuery(params)}`)
}

export function adminNonFoodUploads(params = {}) {
  return request(`/api/admin/non-food-uploads${toQuery(params)}`)
}

export function adminUsers(params = {}) {
  return request(`/api/admin/users${toQuery(params)}`)
}

export function adminDeleteUser(id) {
  return request(`/api/admin/users/${id}`, {
    method: 'DELETE'
  })
}

function toQuery(params = {}) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, value)
    }
  })
  const text = query.toString()
  return text ? `?${text}` : ''
}

export function adminMeals(params = {}) {
  return request(`/api/admin/meals${toQuery(params)}`)
}

export function adminMealAnalytics(params = {}) {
  return request(`/api/admin/meals/analytics${toQuery(params)}`)
}

export function adminDeleteMeal(id) {
  return request(`/api/admin/meals/${id}`, {
    method: 'DELETE'
  })
}

export function adminKnowledgeChunks(params = {}) {
  return request(`/api/admin/knowledge/chunks${toQuery(params)}`)
}

export function adminAddKnowledgeChunk(payload) {
  return request('/api/admin/knowledge/chunks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function adminDeleteKnowledgeChunk(id) {
  return request(`/api/admin/knowledge/chunks/${id}`, {
    method: 'DELETE'
  })
}

export function adminUpdateKnowledgeChunk(id, payload) {
  return request(`/api/admin/knowledge/chunks/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function adminReindexKnowledge() {
  return request('/api/admin/knowledge/reindex', {
    method: 'POST'
  })
}

export function adminRagEvaluation() {
  return request('/api/admin/knowledge/evaluation')
}

export function adminWeeklyReportRuns(params = {}) {
  return request(`/api/admin/weekly-report-runs${toQuery(params)}`)
}

export function adminWeeklyReportRunDetail(runId) {
  return request(`/api/admin/weekly-report-runs/${encodeURIComponent(runId)}`)
}

export function adminStartAssistantEvaluation(mode = 'QUICK') {
  return request('/api/admin/assistant/evaluation', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode })
  })
}

export function adminAssistantEvaluationStatus() {
  return request('/api/admin/assistant/evaluation')
}

export function adminAssistantEvaluationHistory() {
  return request('/api/admin/assistant/evaluation/history')
}

export function adminAssistantEvaluationHistoryDetail(runId, signal) {
  return request(`/api/admin/assistant/evaluation/history/${encodeURIComponent(runId)}`, { signal })
}

export function adminCompareAssistantEvaluations(baseRunId, candidateRunId) {
  return request(`/api/admin/assistant/evaluation/history/compare${toQuery({ baseRunId, candidateRunId })}`)
}

export function adminAssistantEvaluationReviews(runId) {
  return request(`/api/admin/assistant/evaluation/history/${encodeURIComponent(runId)}/reviews`)
}

export function adminReviewAssistantEvaluationCase(runId, caseId, payload) {
  return request(`/api/admin/assistant/evaluation/history/${encodeURIComponent(runId)}/cases/${encodeURIComponent(caseId)}/review`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function adminAssistantEvaluationCandidates() {
  return request('/api/admin/assistant/evaluation/candidates')
}

export function adminMealCorrections(page = 0, signal) {
  return request(`/api/admin/meal-corrections?page=${page}`, { signal })
}
export function adminReviewMealCorrection(id, payload) {
  return request(`/api/admin/meal-corrections/${id}/review`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}
export function adminMealCorrectionEvaluation(signal) {
  return request('/api/admin/meal-corrections/evaluation', { signal })
}
export function adminRunMealEvaluation() { return request('/api/admin/meal-corrections/evaluation/runs', { method: 'POST' }) }
export function adminMealEvaluationHistory(page = 0, signal) { return request(`/api/admin/meal-corrections/evaluation/runs?page=${page}`, { signal }) }
export function adminMealEvaluationDetail(id, signal) { return request(`/api/admin/meal-corrections/evaluation/runs/${id}`, { signal }) }
export function adminCompareMealEvaluations(baseId, candidateId) { return request(`/api/admin/meal-corrections/evaluation/compare?baseId=${baseId}&candidateId=${candidateId}`) }

export function adminAddAssistantEvaluationCandidate(payload) {
  return request('/api/admin/assistant/evaluation/candidates', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function adminUpdateAssistantEvaluationCandidateStatus(id, status) {
  return request(`/api/admin/assistant/evaluation/candidates/${id}/status`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status })
  })
}

export function adminTaskFailures(params = {}, signal) {
  return request(`/api/admin/task-failures${toQuery(params)}`, { signal })
}
export function adminTaskFailureSummary(signal) { return request('/api/admin/task-failures/summary', { signal }) }
export function adminTaskFailureDetail(id, signal) { return request(`/api/admin/task-failures/${id}`, { signal }) }
export function adminTaskFailureAction(id, action, payload) {
  return request(`/api/admin/task-failures/${id}/${action}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}
