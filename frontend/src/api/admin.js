// @ts-check
import { request, jsonOptions } from './transport.js'
import { toQuery } from './query.js'

/** @param {AbortSignal} [signal] */
export function adminAiBudget(signal) { return request('/api/admin/ai-budget', { signal }) }

/** @param {AbortSignal} [signal] */
export function adminAiCostSettings(signal) { return request('/api/admin/ai-cost-settings', { signal }) }

/** @param {unknown} body */
export function saveAdminAiCostSettings(body) { return request('/api/admin/ai-cost-settings', jsonOptions('PUT', body)) }

/** @param {number} version */
export function resetAdminAiCostSettings(version) { return request('/api/admin/ai-cost-settings/reset', jsonOptions('POST', { version })) }

/** @param {AbortSignal} [signal] */
export function adminAiSettings(signal) {
  return request('/api/admin/ai-settings', { signal })
}

/** @param {unknown} data */
export function saveAdminAiSettings(data) {
  return request('/api/admin/ai-settings', jsonOptions('PUT', data))
}

/** @param {number} version */
export function resetAdminAiSettings(version) {
  return request('/api/admin/ai-settings/reset', jsonOptions('POST', { version }))
}

/** @param {number} version */
export function testAdminAiSettings(version) {
  return request('/api/admin/ai-settings/test', jsonOptions('POST', { version }))
}

/**
 * =========================
 * 管理员后台接口
 * =========================
 */

export function adminStats() {
  return request('/api/admin/stats')
}

/** @param {AbortSignal} [signal] */
export function adminDashboard(signal) {
  return request('/api/admin/dashboard', { signal })
}

/** @param {import('./contracts').Query} [params] @param {AbortSignal} [signal] */
export function adminAuditLogs(params = {}, signal) {
  return request(`/api/admin/audit-logs${toQuery(params)}`, { signal })
}

/** @param {import('./contracts').Query} [params] @param {AbortSignal} [signal] */
export function adminNonFoodUploads(params = {}, signal) {
  return request(`/api/admin/non-food-uploads${toQuery(params)}`, { signal })
}

/** @param {import("./contracts").Query} [params] @param {AbortSignal} [signal] @returns {Promise<import("./contracts").Page<import("./contracts").AdminUser>>} */
export function adminUsers(params = {}, signal) {
  return request(`/api/admin/users${toQuery(params)}`, { signal })
}

/** @param {number} id */
export function adminDeleteUser(id) {
  return request(`/api/admin/users/${id}`, {
    method: 'DELETE'
  })
}

/** @param {number} id @param {boolean} enabled @returns {Promise<import('./contracts').AdminUser>} */
export function adminSetUserEnabled(id, enabled) {
  return request(`/api/admin/users/${id}/status`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled })
  })
}

/** @param {import("./contracts").Query} [params] @param {AbortSignal} [signal] @returns {Promise<import("./contracts").Page<import("./contracts").AdminMeal>>} */
export function adminMeals(params = {}, signal) {
  return request(`/api/admin/meals${toQuery(params)}`, { signal })
}

/** @param {import('./contracts').Query} [params] @param {AbortSignal} [signal] */
export function adminMealAnalytics(params = {}, signal) {
  return request(`/api/admin/meals/analytics${toQuery(params)}`, { signal })
}

/** @param {number} id */
export function adminDeleteMeal(id) {
  return request(`/api/admin/meals/${id}`, {
    method: 'DELETE'
  })
}

/** @param {import("./contracts").Query} [params] @param {AbortSignal} [signal] @returns {Promise<import("./contracts").Page<import("./contracts").KnowledgeChunk>>} */
export function adminKnowledgeChunks(params = {}, signal) {
  return request(`/api/admin/knowledge/chunks${toQuery(params)}`, { signal })
}

/** @param {unknown} payload */
export function adminAddKnowledgeChunk(payload) {
  return request('/api/admin/knowledge/chunks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

/** @param {number} id @param {number} expectedRevision */
export function adminDeleteKnowledgeChunk(id, expectedRevision) {
  return request(`/api/admin/knowledge/chunks/${id}?expectedRevision=${expectedRevision}`, {
    method: 'DELETE'
  })
}

/** @param {number} id @param {unknown} payload */
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

/** @param {AbortSignal} [signal] */
export function adminKnowledgeIndexing(signal) {
  return request('/api/admin/knowledge/indexing', { signal })
}

/** @param {AbortSignal} [signal] */
export function adminEmbeddingSettings(signal) {
  return request('/api/admin/knowledge/embedding-settings', { signal })
}

/** @param {unknown} payload */
export function saveAdminEmbeddingSettings(payload) {
  return request('/api/admin/knowledge/embedding-settings', jsonOptions('PUT', payload))
}

/** @param {number} version */
export function testAdminEmbeddingSettings(version) {
  return request('/api/admin/knowledge/embedding-settings/test', { ...jsonOptions('POST', { version }), timeoutMs: 30000 })
}

/** @param {number} version */
export function previewAdminEmbeddingSettings(version) {
  return request('/api/admin/knowledge/embedding-settings/preview', jsonOptions('POST', { version }))
}

/** @param {number} version @param {string} [previewToken] */
export function applyAdminEmbeddingSettings(version, previewToken) {
  return request('/api/admin/knowledge/embedding-settings/apply', jsonOptions('POST', { version, ...(previewToken ? { previewToken } : {}) }))
}

/** @param {string} [mode] @param {number[]} [chunkIds] @param {string} [splitterStrategy] */
export function adminPreviewKnowledge(mode, chunkIds, splitterStrategy) {
  return request('/api/admin/knowledge/indexing/preview', jsonOptions('POST', { mode, ...(chunkIds ? { chunkIds } : {}), ...(!chunkIds && splitterStrategy ? { splitterStrategy } : {}) }))
}

/** @param {string} [mode] @param {number[]} [chunkIds] @param {string} [splitterStrategy] @param {string} [previewToken] */
export function adminBuildKnowledge(mode, chunkIds, splitterStrategy, previewToken) {
  return request('/api/admin/knowledge/indexing', jsonOptions('POST', { mode, previewToken, ...(chunkIds ? { chunkIds } : {}), ...(!chunkIds && splitterStrategy ? { splitterStrategy } : {}) }))
}

/** @param {import('./contracts').Query} [params] @param {AbortSignal} [signal] */
export function adminKnowledgeContentHistory(params = {}, signal) { return request(`/api/admin/knowledge/content-history${toQuery(params)}`, { signal }) }

/** @param {number} id @param {AbortSignal} [signal] */
export function adminKnowledgeContentVersion(id, signal) { return request(`/api/admin/knowledge/content-history/${id}`, { signal }) }

/** @param {number} id @param {number} expectedRevision @param {unknown} reason */
export function adminRestoreKnowledgeContent(id, expectedRevision, reason) { return request(`/api/admin/knowledge/content-history/${id}/restore`, jsonOptions('POST', { expectedRevision, reason })) }

/** @param {import('./contracts').Query} [params] @param {AbortSignal} [signal] */
export function adminKnowledgeJobHistory(params = {}, signal) {
  return request(`/api/admin/knowledge/indexing/jobs${toQuery(params)}`, { signal })
}

/** @param {string | number} id @param {AbortSignal} [signal] */
export function adminKnowledgeJobDetails(id, signal) {
  return request(`/api/admin/knowledge/indexing/jobs/${id}`, { signal })
}

/** @param {string | number} id @param {string} action @param {string} [mode] @param {string} [splitterStrategy] */
export function adminKnowledgeJobAction(id, action, mode, splitterStrategy) {
  return request(`/api/admin/knowledge/indexing/jobs/${id}/${action}`, jsonOptions('POST', { ...(mode ? { mode } : {}), ...(action === 'resubmit' && splitterStrategy ? { splitterStrategy } : {}) }))
}

export function adminRagEvaluation() {
  return request('/api/admin/knowledge/evaluation')
}

/** @param {import('./contracts').Query} [params] */
export function adminWeeklyReportRuns(params = {}) {
  return request(`/api/admin/weekly-report-runs${toQuery(params)}`)
}

/** @param {string | number} runId */
export function adminWeeklyReportRunDetail(runId) {
  return request(`/api/admin/weekly-report-runs/${encodeURIComponent(runId)}`)
}

/** @param {string} [mode] */
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

/** @param {string | number} runId @param {AbortSignal} [signal] */
export function adminAssistantEvaluationHistoryDetail(runId, signal) {
  return request(`/api/admin/assistant/evaluation/history/${encodeURIComponent(runId)}`, { signal })
}

/** @param {string | number} baseRunId @param {string | number} candidateRunId */
export function adminCompareAssistantEvaluations(baseRunId, candidateRunId) {
  return request(`/api/admin/assistant/evaluation/history/compare${toQuery({ baseRunId, candidateRunId })}`)
}

/** @param {string | number} runId */
export function adminAssistantEvaluationReviews(runId) {
  return request(`/api/admin/assistant/evaluation/history/${encodeURIComponent(runId)}/reviews`)
}

/** @param {string | number} runId @param {string} caseId @param {unknown} payload */
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

/** @param {number} [page] @param {AbortSignal} [signal] */
export function adminMealCorrections(page = 0, signal) {
  return request(`/api/admin/meal-corrections?page=${page}`, { signal })
}

/** @param {number} id @param {unknown} payload */
export function adminReviewMealCorrection(id, payload) {
  return request(`/api/admin/meal-corrections/${id}/review`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}

/** @param {AbortSignal} [signal] */
export function adminMealCorrectionEvaluation(signal) {
  return request('/api/admin/meal-corrections/evaluation', { signal })
}

export function adminRunMealEvaluation() { return request('/api/admin/meal-corrections/evaluation/runs', { method: 'POST' }) }

/** @param {number} [page] @param {AbortSignal} [signal] */
export function adminMealEvaluationHistory(page = 0, signal) { return request(`/api/admin/meal-corrections/evaluation/runs?page=${page}`, { signal }) }

/** @param {number} id @param {AbortSignal} [signal] */
export function adminMealEvaluationDetail(id, signal) { return request(`/api/admin/meal-corrections/evaluation/runs/${id}`, { signal }) }

/** @param {number} baseId @param {number} candidateId */
export function adminCompareMealEvaluations(baseId, candidateId) { return request(`/api/admin/meal-corrections/evaluation/compare?baseId=${baseId}&candidateId=${candidateId}`) }

/** @param {unknown} payload */
export function adminAddAssistantEvaluationCandidate(payload) {
  return request('/api/admin/assistant/evaluation/candidates', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

/** @param {number} id @param {string} status */
export function adminUpdateAssistantEvaluationCandidateStatus(id, status) {
  return request(`/api/admin/assistant/evaluation/candidates/${id}/status`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status })
  })
}

/** @param {import('./contracts').Query} [params] @param {AbortSignal} [signal] */
export function adminTaskFailures(params = {}, signal) {
  return request(`/api/admin/task-failures${toQuery(params)}`, { signal })
}

/** @param {AbortSignal} [signal] */
export function adminTaskFailureSummary(signal) { return request('/api/admin/task-failures/summary', { signal }) }

/** @param {string | number} id @param {AbortSignal} [signal] */
export function adminTaskFailureDetail(id, signal) { return request(`/api/admin/task-failures/${id}`, { signal }) }

/** @param {string | number} id @param {string} action @param {unknown} payload */
export function adminTaskFailureAction(id, action, payload) {
  return request(`/api/admin/task-failures/${id}/${action}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}
