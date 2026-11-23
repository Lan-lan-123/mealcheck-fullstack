// @ts-check
import { publishDietChange } from '../utils/dietDataChanges.js'
import { ResponseValidationError } from './validation.js'
import { request, jsonOptions, resetCsrf, clearSession } from './transport.js'
import { toQuery } from './query.js'
export { ApiError, setSession, clearSession, getUser, restoreSession } from './transport.js'
export { askDietAssistant, streamDietAssistant, cancelAssistantRequest } from './assistant.js'
export {
  adminAiBudget,
  adminAiCostSettings,
  saveAdminAiCostSettings,
  resetAdminAiCostSettings,
  adminAiSettings,
  saveAdminAiSettings,
  resetAdminAiSettings,
  testAdminAiSettings,
  adminStats,
  adminDashboard,
  adminAuditLogs,
  adminNonFoodUploads,
  adminUsers,
  adminDeleteUser,
  adminSetUserEnabled,
  adminMeals,
  adminMealAnalytics,
  adminDeleteMeal,
  adminKnowledgeChunks,
  adminAddKnowledgeChunk,
  adminDeleteKnowledgeChunk,
  adminUpdateKnowledgeChunk,
  adminReindexKnowledge,
  adminKnowledgeIndexing,
  adminEmbeddingSettings,
  saveAdminEmbeddingSettings,
  testAdminEmbeddingSettings,
  previewAdminEmbeddingSettings,
  applyAdminEmbeddingSettings,
  adminPreviewKnowledge,
  adminBuildKnowledge,
  adminKnowledgeContentHistory,
  adminKnowledgeContentVersion,
  adminRestoreKnowledgeContent,
  adminKnowledgeJobHistory,
  adminKnowledgeJobDetails,
  adminKnowledgeJobAction,
  adminRagEvaluation,
  adminWeeklyReportRuns,
  adminWeeklyReportRunDetail,
  adminStartAssistantEvaluation,
  adminAssistantEvaluationStatus,
  adminAssistantEvaluationHistory,
  adminAssistantEvaluationHistoryDetail,
  adminCompareAssistantEvaluations,
  adminAssistantEvaluationReviews,
  adminReviewAssistantEvaluationCase,
  adminAssistantEvaluationCandidates,
  adminMealCorrections,
  adminReviewMealCorrection,
  adminMealCorrectionEvaluation,
  adminRunMealEvaluation,
  adminMealEvaluationHistory,
  adminMealEvaluationDetail,
  adminCompareMealEvaluations,
  adminAddAssistantEvaluationCandidate,
  adminUpdateAssistantEvaluationCandidateStatus,
  adminTaskFailures,
  adminTaskFailureSummary,
  adminTaskFailureDetail,
  adminTaskFailureAction
} from './admin.js'
/** @param {AbortSignal} [signal] */
export function aiBudget(signal) { return request('/api/profile/ai-budget', { signal }) }
/** @param {AbortSignal} [signal] @returns {Promise<import('./contracts').TodayMeals>} */
export function todayMeals(signal) { return request('/api/meals/today', { signal }) }
/** @param {import('./contracts').NextMealConstraints} body @param {AbortSignal} [signal] @returns {Promise<import('./contracts').NextMealRecommendations>} */
export function recommendNextMeal(body, signal) { return request('/api/meals/next-meal', { ...jsonOptions('POST', body), signal }) }
/** @param {AbortSignal} [signal] @returns {Promise<import('./contracts').DietPreferences>} */
export function dietPreferences(signal) { return request('/api/profile/diet-preferences', { signal }) }
/** @param {import('./contracts').DietPreferences} body @returns {Promise<import('./contracts').DietPreferences>} */
export function saveDietPreferences(body) { return mutateDiet('/api/profile/diet-preferences', jsonOptions('PUT', body), 'diet-preferences') }
/** @param {AbortSignal} [signal] */
export function personalAiCostSettings(signal) { return request('/api/profile/ai-cost-settings', { signal }) }
/** @param {unknown} body */
export function savePersonalAiCostSettings(body) { return request('/api/profile/ai-cost-settings', jsonOptions('PUT', body)) }

/** @param {string} path @param {import('./contracts').RequestOptions} options @param {string} kind */
async function mutateDiet(path, options, kind) { const result = await request(path, options); publishDietChange(kind); return result }

/** @param {import('./contracts').ManualMealInput} body @returns {Promise<import('./contracts').MealRecord>} */
export function createManualMeal(body) { return mutateDiet('/api/meals/manual', jsonOptions('POST', body), 'meals') }
/** @param {number} id @param {import('./contracts').MealMetadataUpdate} body @returns {Promise<import('./contracts').MealRecord>} */
export function updateMealMetadata(id, body) { return mutateDiet(`/api/meals/${id}/metadata`, jsonOptions('PUT', body), 'meals') }
/** @param {import('./contracts').SkippedMealInput} body @param {boolean} [undo] */
export function declareSkippedMeal(body, undo = false) { return mutateDiet('/api/meals/skipped', jsonOptions(undo ? 'DELETE' : 'PUT', body), 'meals') }
/** @param {string} from @param {string} to @param {AbortSignal} [signal] */
export function mealCoverage(from, to, signal) { return request(`/api/meals/coverage?from=${from}&to=${to}`, { signal }) }
/** @param {{ page?: number; size?: number }} [params] @param {AbortSignal} [signal] */
export function behaviorGoals({ page = 0, size = 20 } = {}, signal) { return request(`/api/profile/behavior-goals?page=${page}&size=${size}`, { signal }) }
/** @param {import('./contracts').BehaviorGoalInput} body @param {number | null} [id] */
export function saveBehaviorGoal(body, id) { return mutateDiet(`/api/profile/behavior-goals${id ? `/${id}` : ''}`, jsonOptions(id ? 'PUT' : 'POST', body), 'behavior-goals') }
/** @param {{ page?: number; size?: number; from?: string; to?: string }} [params] @param {AbortSignal} [signal] */
export function actionPlans({ from, to, page = 0, size = 20 } = {}, signal) { return request(`/api/plans?page=${page}&size=${size}${from && to ? `&from=${from}&to=${to}` : ''}`, { signal }) }
/** @param {import('./contracts').ActionPlanInput} body @param {number | null} [id] */
export function saveActionPlan(body, id) { return mutateDiet(`/api/plans${id ? `/${id}` : ''}`, jsonOptions(id ? 'PUT' : 'POST', body), 'plans') }
/** @param {number} id @param {import('./contracts').AnswerFeedbackInput} body */
export function answerFeedback(id, body) { return request(`/api/assistant/messages/${id}/feedback`, jsonOptions('PUT', body)) }
/** @param {number} id */
export function getAnswerFeedback(id) { return request(`/api/assistant/messages/${id}/feedback`) }
/** @param {number} page @param {AbortSignal} [signal] */
export function weeklyReportHistory(page, signal) { return request(`/api/reports/weekly/history?page=${page}&size=20`, { signal }) }
/** @param {number} id */
export function archivedWeeklyReport(id) { return request(`/api/reports/weekly/history/${id}`) }

/** @param {import('./contracts').RegistrationInput} payload */
export async function register(payload) {
  const result = await request('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  resetCsrf(); return result
}

/** @param {import('./contracts').LoginInput} payload */
export async function login(payload) {
  const result = await request('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  resetCsrf(); return result
}

export function captcha() {
  return request('/api/auth/captcha')
}

export async function logout() {
  const result = await request('/api/auth/logout', {
    method: 'POST'
  })
  clearSession(); return result
}

/** @param {string} password */
export function deleteCurrentAccount(password) {
  return request('/api/profile/account', {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password })
  })
}

/** @param {File} file @param {string} goal @param {string} key @param {AbortSignal} [signal] @returns {Promise<import('./contracts').MealAnalysisState>} */
export async function analyzeMeal(file, goal, key, signal) {
  const form = new FormData()
  form.append('image', file)
  form.append('goal', goal)
  const state = await request('/api/meals/analysis-requests', {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    signal,
    body: form
  })
  if (state.key !== key) throw new ResponseValidationError('/api/meals/analysis-requests.key')
  return state
}
/** @param {string} key @param {AbortSignal} [signal] @returns {Promise<import("./contracts").MealAnalysisState>} */
export function mealAnalysisRequest(key, signal) {
  return request(`/api/meals/analysis-requests/${encodeURIComponent(key)}`, { signal })
}
/** @param {number} id @param {{foods: Pick<import('./contracts').FoodItem, 'name' | 'category' | 'attributes'>[]; expectedRevision: number; reason: string}} payload @returns {Promise<import('./contracts').MealAnalysisResult>} */
export function correctMeal(id, payload) {
  return request(`/api/meals/${id}/corrections`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}

/** @param {import("./contracts").Query} [params] @param {AbortSignal} [signal] @returns {Promise<import("./contracts").Page<import("./contracts").MealSummary>>} */
export function listMealSummaries(params = {}, signal) {
  return request(`/api/meals/summaries${toQuery(params)}`, { signal })
}

/** @param {number} id @param {AbortSignal} [signal] @returns {Promise<import("./contracts").MealRecord>} */
export function mealDetail(id, signal) {
  return request(`/api/meals/${id}`, { signal })
}

/** @param {import("./contracts").Query} [params] @param {AbortSignal} [signal] @returns {Promise<import("./contracts").Page<import("./contracts").MealRecord> | import("./contracts").MealRecord[]>} */
export function listMeals(params = {}, signal) {
  return request(`/api/meals${toQuery(params)}`, { signal })
}

/** @param {number} [days] @returns {Promise<import('./contracts').WeeklyReport>} */
export function weeklyReport(days = 7) {
  return request(`/api/reports/weekly?days=${days}`)
}

/** @param {number} [days] @param {AbortSignal} [signal] @returns {Promise<import("./contracts").WeeklyReportJob>} */
export function requestWeeklyReport(days = 7, signal) {
  return request(`/api/reports/weekly/jobs?days=${days}`, { method: 'POST', signal })
}

/** @param {number} id @param {AbortSignal} [signal] @returns {Promise<import("./contracts").WeeklyReportJob>} */
export function weeklyReportJob(id, signal) {
  return request(`/api/reports/weekly/jobs/${encodeURIComponent(id)}`, { signal })
}

/** @param {AbortSignal} [signal] @returns {Promise<import('./contracts').WeeklyReport | null>} */
export function latestWeeklyReport(signal) {
  return request('/api/reports/weekly/latest', { signal })
}

/** @param {AbortSignal} [signal] @returns {Promise<import("./contracts").DietGoal | null>} */
export function currentGoal(signal) {
  return request('/api/profile/goals/current', { signal })
}

/** @param {import('./contracts').DietGoal} payload @returns {Promise<import('./contracts').DietGoal>} */
export function updateCurrentGoal(payload) {
  return mutateDiet('/api/profile/goals/current', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }, 'diet-goal')
}

/** @param {number} [days] @param {AbortSignal} [signal] @returns {Promise<import('./contracts').MealTrend>} */
export function mealTrends(days = 30, signal) {
  return request(`/api/meals/trends?days=${days}`, { signal })
}

/** @param {AbortSignal} [signal] */
export function healthCheck(signal) {
  return request('/api/health', { signal })
}

/** @param {number} id */
export function deleteMeal(id) {
  return request(`/api/meals/${id}`, {
    method: 'DELETE'
  })
}

/** @param {number} id @param {AbortSignal} [signal] @returns {Promise<import('./contracts').NutritionResponse>} */
export function mealNutrition(id, signal) {
  return request(`/api/meals/${id}/nutrition`, { signal })
}

/** @param {number} id @param {import('./contracts').NutritionUpdate} payload @param {AbortSignal} [signal] @returns {Promise<import('./contracts').NutritionResponse>} */
export function updateMealNutrition(id, payload, signal) {
  return request(`/api/meals/${id}/nutrition`, {
    method: 'PUT', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
}

/** @returns {Promise<import('./contracts').ConversationSummary[]>} */
export function assistantConversations() {
  return request('/api/assistant/conversations')
}

/** @param {number} id @param {{ before?: string; limit?: number; signal?: AbortSignal }} [options] @returns {Promise<import('./contracts').ConversationDetail>} */
export function assistantConversation(id, { before, limit = 40, signal } = {}) {
  return request(`/api/assistant/conversations/${id}${toQuery({ before, limit })}`, { signal })
}

/** @param {number} id */
export function deleteAssistantConversation(id) {
  return request(`/api/assistant/conversations/${id}`, { method: 'DELETE' })
}

/** @returns {Promise<{items: string[]}>} */
export function assistantProactiveAdvice() {
  return request('/api/assistant/proactive')
}

export function reindexKnowledge() {
  return request('/api/knowledge/reindex', {
    method: 'POST'
  })
}
