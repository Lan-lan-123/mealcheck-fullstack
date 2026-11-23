// @ts-check
/** @template T @typedef {(value: unknown, path: string) => T} Validator */

export class ResponseValidationError extends Error {
  /** @param {string} path */
  constructor(path) {
    super('服务返回的数据格式异常，请重新加载后重试。')
    this.name = 'ResponseValidationError'
    this.code = 'INVALID_API_RESPONSE'
    this.path = path
  }
}
/** @param {unknown} value @param {string} path @returns {Record<string, unknown>} */
function record(value, path) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ResponseValidationError(path)
  return /** @type {Record<string, unknown>} */ (value)
}
/** @type {Validator<string>} */
const string = (value, path) => {
  if (typeof value !== 'string') throw new ResponseValidationError(path)
  return value
}
/** @type {Validator<boolean>} */
const boolean = (value, path) => {
  if (typeof value !== 'boolean') throw new ResponseValidationError(path)
  return value
}
/** @param {number} [min] @param {number} [max] @param {boolean} [integer] @returns {Validator<number>} */
const number = (min = 0, max = Infinity, integer = false) => (value, path) => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max || (integer && !Number.isSafeInteger(value))) throw new ResponseValidationError(path)
  return value
}
const id = number(1, Number.MAX_SAFE_INTEGER, true), version = number(0, Number.MAX_SAFE_INTEGER, true), score = number(0, 100)
/** @template {string} T @param {readonly T[]} values @returns {Validator<T>} */
const oneOf = values => (value, path) => {
  const parsed = string(value, path)
  if (!values.includes(/** @type {T} */ (parsed))) throw new ResponseValidationError(path)
  return /** @type {T} */ (parsed)
}
/** @template T @param {Validator<T>} validator @returns {Validator<T | undefined>} */
const optional = validator => (value, path) => value === undefined ? undefined : validator(value, path)
/** @template T @param {Validator<T>} validator @returns {Validator<T | null>} */
const nullable = validator => (value, path) => value == null ? null : validator(value, path)
/** @template T @param {Validator<T>} validator @param {T} fallback @returns {Validator<T>} */
const defaulted = (validator, fallback) => (value, path) => value == null ? fallback : validator(value, path)
/** @template T @param {Validator<T>} validator @returns {Validator<T[]>} */
const array = validator => (value, path) => {
  if (!Array.isArray(value)) throw new ResponseValidationError(path)
  return value.map((item, index) => validator(item, `${path}[${index}]`))
}
/** @template {Record<string, Validator<unknown>>} S @param {S} shape @returns {Validator<{[K in keyof S]: ReturnType<S[K]>}>} */
const object = shape => (value, path) => {
  const source = record(value, path)
  return /** @type {{[K in keyof S]: ReturnType<S[K]>}} */ (Object.fromEntries(Object.entries(shape).map(([key, validator]) => [key, validator(source[key], `${path}.${key}`)])))
}
/** @template T @param {Validator<T>} validator @returns {Validator<Record<string,T>>} */
const dictionary = validator => (value, path) => Object.fromEntries(Object.entries(record(value, path)).map(([key, item]) => [key, validator(item, `${path}.${key}`)]))
const strings = array(string), counts = dictionary(version)
const plan = object({ id, title: string, actionText: string, sourceType: string, sourceRecordId: nullable(id), sourceMessageId: nullable(id),
  plannedDate: string, status: oneOf(/** @type {const} */ (['OPEN', 'DONE', 'SKIPPED'])), note: nullable(string), version,
  completedAt: nullable(string), sourcePeriodStart: nullable(string), sourcePeriodEnd: nullable(string) })
const slot = oneOf(/** @type {const} */ (['RECORDED', 'SKIPPED', 'UNKNOWN']))
/** @type {Validator<import('./contracts').TodayMeals>} */
export const todayMeals = (value, path) => {
  const parsed = object({ date: string, businessZone: string, totalMeals: version,
    slots: object({ BREAKFAST: slot, LUNCH: slot, DINNER: slot, SNACK: slot }), reviewFoodCount: version, reviewMealCount: version,
    reviews: array(object({ recordId: id, mealType: string, foods: strings, foodCount: version })), pendingPlanCount: version, pendingPlans: array(plan) })(value, path)
  if (parsed.reviewMealCount < parsed.reviews.length || parsed.pendingPlanCount < parsed.pendingPlans.length
    || parsed.reviews.some(item => item.foodCount !== item.foods.length) || parsed.reviewFoodCount < parsed.reviews.reduce((sum, item) => sum + item.foodCount, 0)
    || parsed.pendingPlans.some(item => item.status !== 'OPEN' || item.plannedDate !== parsed.date)) throw new ResponseValidationError(path)
  return parsed
}
const nextConstraints = object({ mealType: oneOf(/** @type {const} */ (['BREAKFAST', 'LUNCH', 'DINNER'])), scene: oneOf(/** @type {const} */ (['CANTEEN', 'TAKEOUT', 'HOME'])), budget: number(1, 500, true), availableMinutes: number(5, 120, true) })
/** @type {Validator<import('./contracts').NextMealRecommendations>} */
export const nextMealRecommendations = (value, path) => {
  const parsed = object({ date: string, constraints: nextConstraints, goal: string, preferenceSummary: string, recentMealCount: number(0, 10, true),
    context: string, costBasis: string, timeBasis: string, message: string,
    options: array(object({ id: string, title: string, foods: strings, costMin: number(0, 500, true), costMax: number(0, 500, true), readyMinutes: number(1, 120, true), reasons: strings, actionText: string })) })(value, path)
  if (parsed.options.length > 3 || new Set(parsed.options.map(item => item.id)).size !== parsed.options.length
    || parsed.options.some(item => item.foods.length !== 3 || item.costMin > item.costMax || item.costMax > parsed.constraints.budget || item.readyMinutes > parsed.constraints.availableMinutes)) throw new ResponseValidationError(path)
  return parsed
}
const scoreBreakdownShape = object({ scoringVersion: string, total: score,
  items: array(object({ rule: string, label: string, points: number(-1000, 1000, true), foods: strings })) })
/** @type {Validator<import('./contracts').ScoreBreakdown>} */
const scoreBreakdown = (value, path) => {
  const parsed = scoreBreakdownShape(value, path)
  if (!parsed.items.length || parsed.items.reduce((sum, item) => sum + item.points, 0) !== parsed.total) throw new ResponseValidationError(path)
  return parsed
}
const foodAttributes = object({ fried: nullable(boolean), highOil: nullable(boolean) })
const food = object({ name: string, category: string, confidence: optional(number(0, 1)), note: nullable(string),
  attributes: nullable(foodAttributes), structureRoles: defaulted(array(object({ role: string, source: string, evidence: string })), []), reviewIssues: defaulted(array(object({ code: string, message: string })), []) })
/** @type {Validator<import('./contracts').FoodItem>} */
const uncertainFood = (value, path) => {
  const parsed = food(value, path)
  if (parsed.confidence === undefined || parsed.confidence >= 0.5) throw new ResponseValidationError(`${path}.confidence`)
  return parsed
}
const reference = object({ id: optional(id), title: string, content: string, category: optional(string), score: optional(number(-Infinity)) })
/** @type {Validator<import('./contracts').MealAnalysisResult>} */
const mealAnalysisShape = object({
  recordId: id, revision: defaulted(version, 0),
  recognition: object({ foods: array(food), demoMode: optional(boolean), uncertainFoods: defaulted(array(uncertainFood), []) }),
  evaluation: object({ score, summary: defaulted(string, ''), riskTags: defaulted(strings, []), categoryCounts: optional(counts), scoreBreakdown: nullable(scoreBreakdown) }),
  scoringVersion: optional(string), scoreChange: nullable(object({ before: score, after: score, previousVersion: string, currentVersion: string })),
  advice: defaulted(string, ''), adviceStatus: defaulted(string, 'LEGACY'), adviceFailureCode: nullable(string), references: optional(array(reference))
})
/** @type {Validator<import('./contracts').MealAnalysisResult>} */
export const mealAnalysisResult = (value, path) => {
  const parsed = mealAnalysisShape(value, path)
  if (parsed.evaluation.scoreBreakdown && parsed.evaluation.scoreBreakdown.total !== parsed.evaluation.score) throw new ResponseValidationError(`${path}.evaluation.scoreBreakdown`)
  if (parsed.scoreChange && parsed.scoreChange.after !== parsed.evaluation.score) throw new ResponseValidationError(`${path}.scoreChange`)
  return parsed
}
/** @type {Validator<import('./contracts').MealAnalysisState>} */
export const mealAnalysisState = (value, path) => {
  const parsed = object({ key: string, status: oneOf(/** @type {const} */ (['PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'RETRYABLE'])),
    result: nullable(mealAnalysisResult), retryAfterSeconds: optional(number()), errorCode: nullable(string),
    stage: optional(oneOf(/** @type {const} */ (['ACCEPTED', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED']))),
    message: optional(string) })(value, path)
  if (parsed.status === 'SUCCEEDED' && !parsed.result) throw new ResponseValidationError(`${path}.result`)
  return { ...parsed, errorCode: parsed.errorCode ?? undefined }
}
const mealFields = { id, createdAt: string, occurredAt: defaulted(string, ''), mealType: defaulted(string, 'UNSPECIFIED'),
  recordSource: defaulted(oneOf(/** @type {const} */ (['PHOTO', 'MANUAL'])), 'PHOTO'), timeConfirmed: defaulted(boolean, false),
  goal: defaulted(string, 'balanced'), score, summary: defaulted(string, '') }
/** @type {Validator<import('./contracts').MealSummary>} */
export const mealSummary = object({ ...mealFields, foodNames: defaulted(strings, []) })
/** @type {Validator<import('./contracts').MealRecord>} */
const mealRecordShape = object({ ...mealFields, foods: array(food), uncertainFoods: defaulted(array(uncertainFood), []), categoryCounts: defaulted(counts, {}), riskTags: defaulted(strings, []),
  advice: defaulted(string, ''), adviceStatus: defaulted(string, 'LEGACY'), adviceFailureCode: nullable(string), imageUrl: nullable(string), revision: defaulted(version, 0), metadataVersion: defaulted(version, 0), note: nullable(string), scoringVersion: defaulted(string, ''), scoreBreakdown: nullable(scoreBreakdown) })
/** @type {Validator<import('./contracts').MealRecord>} */
export const mealRecord = (value, path) => {
  const parsed = mealRecordShape(value, path)
  if (parsed.scoreBreakdown && parsed.scoreBreakdown.total !== parsed.score) throw new ResponseValidationError(`${path}.scoreBreakdown`)
  return parsed
}
/** @template T @param {Validator<T>} item @returns {Validator<import('./contracts').Page<T>>} */
export const pageOf = item => object({ content: array(item), totalElements: version, totalPages: version, page: defaulted(version, 0), size: defaulted(number(1, 50, true), 20) })
/** @type {Validator<import('./contracts').DietGoal>} */
export const dietGoal = object({ goalType: oneOf(/** @type {const} */ (['balanced', 'fat_loss', 'muscle_gain', 'light'])), endDate: nullable(string), note: nullable(string) })
const dietPreferences = object({ style: oneOf(/** @type {const} */ (['OMNIVORE', 'VEGETARIAN', 'VEGAN'])), avoidFoods: strings, dislikedFoods: strings, version })
const metric = object({ label: string, value: number() })
const tracking = object({ periodStart: optional(string), periodEnd: optional(string), totalMeals: optional(version), recordedDays: optional(version), days: optional(version),
  coveragePercent: optional(number(0, 100)), confirmedSkippedSlots: optional(version), unknownSlots: optional(version), explanation: optional(string), plans: optional(counts),
  daily: optional(array(object({ day: string, meals: version, slots: dictionary(oneOf(/** @type {const} */ (['RECORDED', 'SKIPPED', 'UNKNOWN']))) }))),
  goals: optional(array(object({ goal: object({ id, kind: oneOf(/** @type {const} */ (['MEALS_DAILY', 'RECORD_DAYS_WEEKLY'])), target: version, startDate: optional(string), endDate: nullable(string), active: optional(boolean), version: optional(version) }),
    periodStart: string, periodEnd: string, completed: version, remaining: version, achieved: boolean, eligibleDays: optional(version), daysAchieved: optional(version) }))) })
/** @type {Validator<import('./contracts').MealTrend>} */
export const mealTrend = object({ averageScore: score, days: optional(version), total: optional(version), lowScoreCount: optional(version), highScoreCount: optional(version),
  scoreTrend: optional(string), suggestion: optional(string), dailyAverageScores: optional(array(object({ label: string, value: nullable(number(0, 100)) }))), foodCounts: optional(array(metric)), riskTagCounts: optional(array(metric)), tracking: nullable(tracking) })
/** @type {Validator<import('./contracts').WeeklyReport>} */
export const weeklyReport = object({ periodStart: string, periodEnd: string, averageScore: score, totalMeals: version,
  reportText: defaulted(string, ''), nextWeekSuggestions: defaulted(strings, []), riskTotals: defaulted(counts, {}),
  categoryTotals: optional(counts), categoryMealCounts: optional(counts), highlights: optional(strings), days: optional(version),
  goalType: optional(string), generatedAt: nullable(string), generationMode: optional(string), reviewStatus: optional(string),
  agentTrace: optional(strings), coordinationRunId: nullable(string), tracking: nullable(tracking) })
/** @type {Validator<import('./contracts').WeeklyReportJob>} */
export const weeklyReportJob = (value, path) => {
  const parsed = object({ id, status: oneOf(/** @type {const} */ (['PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'DEAD', 'STALE'])), report: nullable(weeklyReport) })(value, path)
  if (parsed.status === 'SUCCEEDED' && !parsed.report) throw new ResponseValidationError(`${path}.report`)
  return parsed
}
const nutrients = object({ energyKcal: nullable(number(0, 1000)), proteinGrams: nullable(number(0, 100)), fatGrams: nullable(number(0, 100)), carbohydrateGrams: nullable(number(0, 100)) })
const completeNutrients = object({ energyKcal: number(), proteinGrams: number(), fatGrams: number(), carbohydrateGrams: number() })
/** @type {Validator<import('./contracts').NutritionResponse>} */
export const nutritionResponse = (value, path) => {
  const parsed = object({ recordId: id, recordRevision: version, version, enabled: boolean,
    status: oneOf(/** @type {const} */ (['DISABLED', 'INCOMPLETE', 'ESTIMATED', 'STALE'])), calculationVersion: optional(string), warning: optional(string),
    items: array(object({ foodIndex: version, name: string, category: optional(string), grams: nullable(number(0.1, 10000)), per100g: nullable(nutrients),
      source: nullable(string), portionSource: nullable(string), estimate: nullable(completeNutrients) })), estimatedFoods: version, totals: nullable(completeNutrients) })(value, path)
  const indices = new Set(parsed.items.map(item => item.foodIndex))
  if (indices.size !== parsed.items.length || parsed.items.some((item, index) => item.foodIndex > 29 || !indices.has(index)) || parsed.estimatedFoods > parsed.items.length) throw new ResponseValidationError(`${path}.items`)
  if (parsed.enabled === (parsed.status === 'DISABLED')) throw new ResponseValidationError(`${path}.enabled`)
  if (['DISABLED', 'STALE'].includes(parsed.status) && (parsed.estimatedFoods !== 0
    || parsed.items.some(item => item.grams !== null || item.per100g !== null || item.estimate !== null))) throw new ResponseValidationError(`${path}.items`)
  if (parsed.status === 'ESTIMATED') {
    if (!parsed.items.length || !parsed.totals || parsed.estimatedFoods !== parsed.items.length
      || parsed.items.some(item => item.grams === null || item.per100g === null || Object.values(item.per100g).some(v => v === null))) throw new ResponseValidationError(`${path}.totals`)
  } else if (parsed.totals !== null) throw new ResponseValidationError(`${path}.totals`)
  return parsed
}
const toolCall = object({ name: string, arguments: string, status: string, round: number(1, 100, true), durationMs: number(0, Number.MAX_SAFE_INTEGER, true),
  origin: oneOf(/** @type {const} */ (['MODEL', 'ORCHESTRATOR'])), resultSummary: defaulted(string, '') })
const responseFields = { reviewStatus: optional(oneOf(/** @type {const} */ (['VERIFIED', 'NOT_REVIEWED', 'UNAVAILABLE', 'NOT_APPLICABLE', 'UNKNOWN']))), answerStatus: optional(oneOf(/** @type {const} */ (['COMPLETE', 'PARTIAL', 'DEGRADED']))), answerReasonCodes: optional(strings), failureCode: nullable(string), upstreamStatus: nullable(number(100, 599, true)),
  toolCalls: optional(array(toolCall)), answer: nullable(string), summary: nullable(string), suggestions: optional(strings), weeklyTrend: nullable(string),
  riskLevel: nullable(oneOf(/** @type {const} */ (['LOW', 'MEDIUM', 'HIGH']))), references: optional(array(reference)),
  conversationId: nullable(id), messageId: nullable(id), requestId: nullable(string), deliveryStatus: optional(oneOf(/** @type {const} */ (['COMPLETE', 'CANCELLED', 'ERROR']))) }
/** @type {Validator<import('./contracts').AssistantResponse>} */
export const assistantResponse = (value, path) => {
  const parsed = object(responseFields)(value, path)
  if (!parsed.answer?.trim() && !parsed.summary?.trim() && !parsed.suggestions?.length && !parsed.weeklyTrend?.trim()) throw new ResponseValidationError(`${path}.answer`)
  return parsed
}
export const conversationSummaries = array(object({ id, title: string, createdAt: optional(string), updatedAt: optional(string) }))
/** @type {Validator<import('./contracts').ConversationDetail>} */
export const conversationDetail = (value, path) => {
  const parsed = object({ id, title: optional(string), hasMore: defaulted(boolean, false), nextBefore: nullable(string),
    messages: array(object({ ...responseFields, role: oneOf(/** @type {const} */ (['user', 'assistant'])), text: string })) })(value, path)
  if (parsed.hasMore && !parsed.nextBefore) throw new ResponseValidationError(`${path}.nextBefore`)
  return parsed
}
export const proactiveAdvice = object({ items: strings })
/** @param {string} event @param {unknown} value @returns {import('./contracts').AssistantEventData | null} */
export function assistantEvent(event, value) {
  const path = `assistant.stream.${event}`
  if (event === 'complete') return assistantResponse(value, path)
  if (event === 'delta') return object({ text: string })(value, path)
  if (event === 'error') return object({ message: string, requestId: optional(string), code: optional(string) })(value, path)
  if (['request', 'queued', 'meta'].includes(event)) return object({ requestId: optional(string), conversationId: optional(id), status: optional(string) })(value, path)
  // Future event names may be safely ignored; recognized events are validated before callbacks.
  return null
}
/** @template T @param {Validator<T>} validator @param {unknown} value @param {string} [path] @returns {T} */
export const parseResponse = (validator, value, path = 'response') => validator(value, path)

/** @param {string} url @param {unknown} value @param {string} [method] @returns {unknown} */
export function validateApiResponse(url, value, method = 'GET') {
  if (method === 'DELETE') return value
  const path = url.split('?')[0]
  if (path === '/api/meals/today') return todayMeals(value, path)
  if (path === '/api/meals/next-meal') return nextMealRecommendations(value, path)
  if (path === '/api/meals/analysis-requests' || /^\/api\/meals\/analysis-requests\//.test(path)) {
    const parsed = mealAnalysisState(value, path)
    if (path.startsWith('/api/meals/analysis-requests/') && parsed.key !== decodeURIComponent(path.split('/')[4])) throw new ResponseValidationError(`${path}.key`)
    return parsed
  }
  if (/^\/api\/meals\/\d+\/nutrition$/.test(path)) {
    const parsed = nutritionResponse(value, path)
    if (parsed.recordId !== Number(path.split('/')[3])) throw new ResponseValidationError(`${path}.recordId`)
    return parsed
  }
  if (/^\/api\/meals\/\d+\/corrections$/.test(path)) {
    const parsed = mealAnalysisResult(value, path)
    if (parsed.recordId !== Number(path.split('/')[3])) throw new ResponseValidationError(`${path}.recordId`)
    return parsed
  }
  if (/^\/api\/meals\/\d+(\/metadata)?$/.test(path)) {
    const parsed = mealRecord(value, path)
    if (parsed.id !== Number(path.split('/')[3])) throw new ResponseValidationError(`${path}.id`)
    return parsed
  }
  if (path === '/api/meals/manual') return mealRecord(value, path)
  if (path === '/api/meals/summaries') return pageOf(mealSummary)(value, path)
  if (path === '/api/meals') {
    const query = new URLSearchParams(url.split('?')[1])
    const paginated = ['page', 'size', 'goal', 'from', 'to', 'minScore', 'maxScore'].some(key => query.has(key))
    return paginated ? pageOf(mealRecord)(value, path) : array(mealRecord)(value, path)
  }
  if (path === '/api/profile/goals/current') return (method === 'GET' ? nullable(dietGoal) : dietGoal)(value, path)
  if (path === '/api/profile/diet-preferences') return dietPreferences(value, path)
  if (path === '/api/meals/trends') return mealTrend(value, path)
  if (path === '/api/reports/weekly/latest') return nullable(weeklyReport)(value, path)
  if (path === '/api/reports/weekly') return weeklyReport(value, path)
  if (/^\/api\/reports\/weekly\/jobs(\/\d+)?$/.test(path)) {
    const parsed = weeklyReportJob(value, path)
    if (path.startsWith('/api/reports/weekly/jobs/') && parsed.id !== Number(path.split('/')[5])) throw new ResponseValidationError(`${path}.id`)
    return parsed
  }
  if (path === '/api/assistant/ask') return assistantResponse(value, path)
  if (path === '/api/assistant/conversations') return conversationSummaries(value, path)
  if (/^\/api\/assistant\/conversations\/\d+$/.test(path)) {
    const parsed = conversationDetail(value, path)
    if (parsed.id !== Number(path.split('/')[4])) throw new ResponseValidationError(`${path}.id`)
    return parsed
  }
  if (path === '/api/assistant/proactive') return proactiveAdvice(value, path)
  return value
}
