// @ts-check
import { waitForPoll, pollTask } from './taskPolling.js'
import { analysisFailure } from './recognitionExplanation.js'

/**
 * @param {File} file @param {string} goal @param {string} key
 * @param {{submit: (file: File, goal: string, key: string, signal?: AbortSignal) => Promise<import('../api/contracts').MealAnalysisState>, signal?: AbortSignal,
 * onRetry?: (retry: {attempt: number, delay: number}) => void, wait?: (ms: number, signal?: AbortSignal) => Promise<void>, random?: () => number, maxRetries?: number}} options
 */
export async function submitMealAnalysisWithRetry(file, goal, key, {
  submit, signal, onRetry = () => {}, wait = waitForPoll, random = Math.random, maxRetries = 2
}) {
  for (let attempt = 0; ; attempt++) {
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
    try {
      const state = await submit(file, goal, key, signal)
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      return state
    } catch (error) {
      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
      // Only explicit admission rejection is safe to resubmit automatically.
      if (error.status !== 429 || !['UPLOAD_BUSY', 'ANALYSIS_BUSY'].includes(error.code) || attempt >= maxRetries) throw error
      const retryAfter = Math.max(1, Number(error.retryAfter) || 1)
      if (retryAfter > 15) throw error
      const delay = Math.max(retryAfter * 1000, 1000 * 2 ** attempt) + Math.floor(random() * 250)
      onRetry({ attempt: attempt + 1, delay })
      await wait(delay, signal)
    }
  }
}

/** @param {import('../api/contracts').MealAnalysisState} initial
 * @param {import('../api/contracts').PollOptions<import('../api/contracts').MealAnalysisState, string>} options */
export async function resolveMealAnalysis(initial, options) {
  return pollTask(initial, initial.key, {
    maxPolls: 420, maxDurationMs: 21 * 60 * 1000, ...options,
    timeoutMessage: '分析仍在后台处理中。刷新页面会自动继续查询，请勿重复新建分析。',
    evaluate: /** @type {(state: import('../api/contracts').MealAnalysisState) => {done: false} | {done: true, result: import('../api/contracts').MealAnalysisResult}} */ (state => {
      if (state.status === 'SUCCEEDED') {
        if (!state.result) throw new Error('分析结果暂时不可用，请稍后重试查询。')
        return { done: true, result: state.result }
      }
      if (['FAILED', 'RETRYABLE'].includes(state.status)) {
        if (state.errorCode === 'ANALYSIS_STORAGE_BUSY') throw Object.assign(new Error('待处理图片存储空间繁忙，请等待已有任务完成后使用原图重试。'), { code: state.errorCode })
        if (state.errorCode === 'UPLOAD_BUSY') throw Object.assign(new Error('上传入口繁忙，请使用原图稍后重试。原请求标识会保留。'), { code: state.errorCode })
        if (state.errorCode === 'AI_BUDGET_EXCEEDED') throw Object.assign(new Error('今日 AI 额度已用尽，请查看额度重置时间，重置后使用原图重试。'), { code: state.errorCode })
        if (state.errorCode === 'AI_BUDGET_UNAVAILABLE') throw Object.assign(new Error('AI 额度服务暂时不可用，请稍后使用原图重试。'), { code: state.errorCode })
        if (state.errorCode) throw Object.assign(new Error(analysisFailure(state.errorCode).message), { code: state.errorCode })
        throw new Error('分析未完成，请使用原图重试。重复点击会复用原请求。')
      }
      if (!['PENDING', 'PROCESSING'].includes(state.status)) throw new Error('无法识别分析任务状态。')
      return { done: false }
    })
  })
}
