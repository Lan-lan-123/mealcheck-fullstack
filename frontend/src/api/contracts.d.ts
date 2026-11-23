export interface SessionUser {
  username: string
  displayName: string
  role: 'USER' | 'ADMIN'
}
export interface ActionPlan {
  id: number; title: string; actionText: string; sourceType: string; sourceRecordId: number | null; sourceMessageId: number | null
  plannedDate: string; status: 'OPEN' | 'DONE' | 'SKIPPED'; note: string | null; version: number
  completedAt: string | null; sourcePeriodStart: string | null; sourcePeriodEnd: string | null
}
export interface TodayMeals {
  date: string; businessZone: string; totalMeals: number; slots: Record<'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK', 'RECORDED' | 'SKIPPED' | 'UNKNOWN'>
  reviewFoodCount: number; reviewMealCount: number; reviews: { recordId: number; mealType: string; foods: string[]; foodCount: number }[]
  pendingPlanCount: number; pendingPlans: ActionPlan[]
}
export interface NextMealConstraints { mealType: 'BREAKFAST' | 'LUNCH' | 'DINNER'; scene: 'CANTEEN' | 'TAKEOUT' | 'HOME'; budget: number; availableMinutes: number }
export interface NextMealOption { id: string; title: string; foods: string[]; costMin: number; costMax: number; readyMinutes: number; reasons: string[]; actionText: string }
export interface NextMealRecommendations {
  date: string; constraints: NextMealConstraints; goal: string; preferenceSummary: string; recentMealCount: number; context: string
  costBasis: string; timeBasis: string; options: NextMealOption[]; message: string
}
export interface AuthSession extends SessionUser {
}
export interface LoginInput {
  username: string; password: string; expectedRole?: SessionUser['role']; captchaId: string; captchaAnswer: string
}
export interface RegistrationInput {
  username: string; password: string; displayName?: string; captchaId: string; captchaAnswer: string
}
export interface SkippedMealInput { day: string; mealType: string }
export interface BehaviorGoalInput {
  kind: string; target: number; startDate: string; endDate: string; active: boolean; expectedVersion?: number
}
export interface ActionPlanInput {
  title: string; actionText: string; plannedDate: string; sourceType: string
  sourceRecordId?: number | null; sourceMessageId?: number | null
  sourcePeriodStart?: string | null; sourcePeriodEnd?: string | null
  status?: ActionPlan['status']; note?: string | null; expectedVersion?: number
}
export interface AnswerFeedbackInput {
  verdict: 'HELPFUL' | 'UNHELPFUL'
  category: 'GENERAL' | 'INCORRECT' | 'IRRELEVANT' | 'UNCLEAR' | 'SERVICE_ERROR'
  note?: string
}
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export type Query = Record<string, string | number | boolean | null | undefined>
export interface FoodAttributes { fried: boolean | null; highOil: boolean | null }
export interface DietPreferences { style: 'OMNIVORE' | 'VEGETARIAN' | 'VEGAN'; avoidFoods: string[]; dislikedFoods: string[]; version: number }
export interface ScoreBreakdown { scoringVersion: string; total: number; items: { rule: string; label: string; points: number; foods: string[] }[] }
export interface FoodItem {
  name: string
  category: string
  confidence?: number
  note?: string | null
  attributes?: FoodAttributes | null
  structureRoles?: { role: string; source: string; evidence: string }[]
  reviewIssues?: { code: string; message: string }[]
}
export interface MealSummary {
  id: number
  createdAt: string
  occurredAt: string
  mealType: string
  recordSource: 'PHOTO' | 'MANUAL'
  timeConfirmed: boolean
  goal: string
  score: number
  summary: string
  foodNames: string[]
}
export interface MealRecord extends Omit<MealSummary, 'foodNames'> {
  foods: FoodItem[]
  uncertainFoods?: FoodItem[]
  categoryCounts: Record<string, number>
  riskTags: string[]
  advice: string
  adviceStatus?: string
  adviceFailureCode?: string | null
  imageUrl: string | null
  revision: number
  metadataVersion: number
  note: string | null
  scoringVersion: string
  scoreBreakdown?: ScoreBreakdown | null
}
export interface MealAnalysisResult {
  recordId: number
  revision: number
  evaluation: { score: number; summary: string; riskTags: string[]; categoryCounts?: Record<string, number>; scoreBreakdown?: ScoreBreakdown | null }
  scoringVersion?: string
  scoreChange?: { before: number; after: number; previousVersion: string; currentVersion: string } | null
  recognition: { foods: FoodItem[]; demoMode?: boolean; uncertainFoods?: FoodItem[] }
  advice: string
  adviceStatus?: string
  adviceFailureCode?: string | null
  references?: AssistantReference[]
}
export type MealAnalysisStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'RETRYABLE'
export interface MealAnalysisState {
  key: string
  status: MealAnalysisStatus
    stage?: 'ACCEPTED' | 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
    message?: string
  result?: MealAnalysisResult | null
  retryAfterSeconds?: number
  errorCode?: string
  connectionInterrupted?: boolean
}
export interface WeeklyReport {
  periodStart: string
  periodEnd: string
  averageScore: number
  totalMeals: number
  reportText: string
  nextWeekSuggestions: string[]
  [field: string]: unknown
}
export type DietGoalType = 'balanced' | 'fat_loss' | 'muscle_gain' | 'light'
export type UserSection = 'overview' | 'records' | 'trends' | 'reports' | 'goals' | 'plans'
export interface UserWorkspaceParameters {
  recordPage: number
  recordSize: number
  recordFilters: RecordFilters
  dataRevision: number
  reportPending: boolean
}
export interface RecordFilters { goal: string; from: string; to: string; minScore: string; maxScore: string }
export interface GoalDraft { goalType: string; endDate: string; note: string }
export interface ProgressTask {
  status: string
    stage?: string
  startedAt?: number
  finishedAt?: number
  message?: string
  connectionInterrupted?: boolean
  errorCode?: string
}
export interface MealTrend {
  days?: number
  total?: number
  averageScore: number
  lowScoreCount?: number
  highScoreCount?: number
  scoreTrend?: string
  suggestion?: string
  dailyAverageScores?: { label: string; value: number | null }[]
  foodCounts?: { label: string; value: number }[]
  riskTagCounts?: { label: string; value: number }[]
}
export type NutrientKey = 'energyKcal' | 'proteinGrams' | 'fatGrams' | 'carbohydrateGrams'
export type Nutrients = Record<NutrientKey, number | null>
export type CompleteNutrients = Record<NutrientKey, number>
export interface NutritionItem {
  foodIndex: number
  name: string
  category?: string
  grams: number | null
  per100g: Nutrients | null
  source: string | null
  portionSource?: string | null
  estimate?: CompleteNutrients | null
}
export interface NutritionResponse {
  recordId: number
  recordRevision: number
  version: number
  enabled: boolean
  status: 'DISABLED' | 'INCOMPLETE' | 'ESTIMATED' | 'STALE'
  calculationVersion?: string
  warning?: string
  items: NutritionItem[]
  estimatedFoods: number
  totals: CompleteNutrients | null
}
export interface NutritionDraftItem {
  foodIndex: number
  name: string
  grams: string
  source: string
  per100g: Record<NutrientKey, string>
}
export interface NutritionUpdate {
  enabled: boolean
  expectedRevision: number
  expectedVersion: number
  items: { foodIndex: number; grams: number | null; source: string | null; per100g: Nutrients }[]
}
export interface MealMetadataUpdate { occurredAt: string; mealType: string; note: string; expectedVersion: number }
export interface ManualMealInput extends MealMetadataUpdate { goal: string; foods: Pick<FoodItem, 'name' | 'category' | 'attributes'>[] }
export type DeliveryStatus = 'STREAMING' | 'COMPLETE' | 'CANCELLED' | 'ERROR'
export interface AssistantReference { id?: number; title: string; content: string; category?: string; score?: number }
export interface AssistantToolCall {
  name: string
  arguments: string
  status: string
  round: number
  durationMs: number
  origin: 'MODEL' | 'ORCHESTRATOR'
  resultSummary: string
}
export interface AssistantResponse {
  reviewStatus?: 'VERIFIED' | 'NOT_REVIEWED' | 'UNAVAILABLE' | 'NOT_APPLICABLE' | 'UNKNOWN'
  answerStatus?: 'COMPLETE' | 'PARTIAL' | 'DEGRADED'
  answerReasonCodes?: string[]
  failureCode?: string | null
  upstreamStatus?: number | null
  toolCalls?: AssistantToolCall[]
  answer?: string | null
  summary?: string | null
  suggestions?: string[]
  weeklyTrend?: string | null
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | null
  references?: AssistantReference[]
  conversationId?: number | null
  messageId?: number | null
  requestId?: string | null
  deliveryStatus?: Exclude<DeliveryStatus, 'STREAMING'>
}
export interface ChatMessage extends Omit<AssistantResponse, 'deliveryStatus'> {
  role: 'user' | 'assistant'
  text: string
  streamKey?: string
  deliveryStatus?: DeliveryStatus
}
export interface ConversationSummary { id: number; title: string; createdAt?: string; updatedAt?: string }
export interface ConversationDetail { id: number; title?: string; messages: ChatMessage[]; hasMore: boolean; nextBefore: string | null }
export interface AssistantHistoryItem { role: ChatMessage['role']; text: string }
export interface DialogApi {
  confirm: (message: string, options?: {title?: string; danger?: boolean; confirmText?: string}) => Promise<boolean>
  prompt: (message: string, options?: {title?: string; inputLabel?: string; inputType?: string; danger?: boolean; confirmText?: string}) => Promise<string | null>
  notify: (message: string, options?: {title?: string}) => Promise<unknown>
}
export interface DietRecordsProps {
  onNew: () => void
  onEdit: (record: MealSummary) => void
  recordPageData: Page<MealSummary>
  recordFiltersDraft: RecordFilters
  setRecordFiltersDraft: import('react').Dispatch<import('react').SetStateAction<RecordFilters>>
  applyRecordFilters: (event: import('react').FormEvent) => void
  resetRecordFilters: () => void
  records: MealSummary[]
  handleDeleteRecord: (id: number) => void
  setCorrectionRecord: (record: MealSummary) => void
  setViewingRecord: (record: MealSummary) => void
  recordSize: number
  setRecordPage: import('react').Dispatch<import('react').SetStateAction<number>>
  setRecordSize: import('react').Dispatch<import('react').SetStateAction<number>>
  loading: boolean
  recordsLoading: boolean
}
export interface AssistantEventData extends AssistantResponse {
  text?: string
  message?: string
  status?: string
  code?: string
}
export type AssistantEvent = 'request' | 'queued' | 'meta' | 'delta' | 'complete' | 'error'
export type AssistantEventHandler = (event: AssistantEvent, value: AssistantEventData) => void
export type WeeklyReportStatus = 'PENDING' | 'RUNNING' | 'RETRY' | 'SUCCEEDED' | 'DEAD' | 'STALE'
export interface WeeklyReportJob {
  id: number
  status: WeeklyReportStatus
  report?: WeeklyReport | null
  connectionInterrupted?: boolean
}
export interface DietGoal {
  goalType: string
  endDate: string | null
  note: string | null
}
export interface AdminUser {
  id: number
  username: string
  displayName: string | null
  role: SessionUser['role']
  enabled: boolean
  createdAt: string
  lastUploadAt: string | null
}
export interface AdminMeal {
  id: number
  username: string
  score: number | null
  summary: string
  foodNames: string[]
  createdAt: string
}
export interface KnowledgeChunk {
  id: number
  contentRevision: number
  title: string | null
  content?: string
  contentPreview: string
  source: string
  category: string
  sourceVersion: string
  tokenCount: number
  embeddingInputTokens?: number | null
  embeddingTotalTokens?: number | null
  hitCount: number
  updatedAt: string
  lastHitAt: string | null
}
export interface RequestOptions extends Omit<RequestInit, 'headers'> {
  headers?: Record<string, string>
  timeoutMs?: number
}
export interface PollOptions<T, K = number> {
  load: (id: K, signal?: AbortSignal) => Promise<T>
  signal?: AbortSignal
  onUpdate?: (state: T) => void
  wait?: (milliseconds: number, signal?: AbortSignal) => Promise<void>
  maxPolls?: number
  maxDurationMs?: number
  random?: () => number
  now?: () => number
  visibility?: Pick<Document, 'hidden' | 'addEventListener' | 'removeEventListener'> | null
}
export type AdminResource = 'dashboard' | 'health' | 'users' | 'meals' | 'analytics' | 'knowledge' | 'audit' | 'nonfood'
export interface AdminActions {
  confirm: (message: string, options?: { danger?: boolean; confirmText?: string }) => Promise<boolean>
  setError: (message: string) => void
  setMessage: (message: string) => void
  setLoading: (loading: boolean) => void
  refresh: (resources: AdminResource[]) => void
}
export interface AdminWorkspaceParameters {
  userPage: number
  userSize: number
  userFilters: Query
  mealPage: number
  mealSize: number
  mealFilters: Query
  knowledgePage: number
  knowledgeSize: number
  knowledgeFilters: Query
  auditPage: number
  auditSize: number
  nonFoodPage: number
  nonFoodSize: number
  nonFoodFilters: Query
}
