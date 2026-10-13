import { waitForPoll } from './weeklyReportJob.js'

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

export async function resolveMealAnalysis(initial, { load, signal, onUpdate = () => {}, wait = waitForPoll, maxPolls = 420 }) {
  let state = initial
  let networkFailures = 0
  for (let poll = 0; ; poll++) {
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
    onUpdate(state)
    if (state.status === 'SUCCEEDED') {
      if (!state.result) throw new Error('分析结果暂时不可用，请稍后重试查询。')
      return state.result
    }
    if (['FAILED', 'RETRYABLE'].includes(state.status)) {
      if (state.errorCode === 'UPLOAD_BUSY') throw Object.assign(new Error('上传入口繁忙，请使用原图稍后重试。原请求标识会保留。'), { code: state.errorCode })
      if (state.errorCode === 'AI_BUDGET_EXCEEDED') throw Object.assign(new Error('今日 AI 额度已用尽，请查看额度重置时间，重置后使用原图重试。'), { code: state.errorCode })
      if (state.errorCode === 'AI_BUDGET_UNAVAILABLE') throw Object.assign(new Error('AI 额度服务暂时不可用，请稍后使用原图重试。'), { code: state.errorCode })
      throw new Error('分析未完成，请使用原图重试。重复点击会复用原请求。')
    }
    if (!['PENDING', 'PROCESSING'].includes(state.status)) throw new Error('无法识别分析任务状态。')
    if (poll >= maxPolls) throw new Error('分析仍在后台处理中。刷新页面会自动继续查询，请勿重复新建分析。')
    await wait(Math.min(10, Math.max(2, state.retryAfterSeconds || 3)) * 1000, signal)
    try { state = await load(state.key, signal); networkFailures = 0 }
    catch (error) {
      if (error.name === 'AbortError' || [401, 403, 404, 410].includes(error.status) || ++networkFailures >= 5) throw error
      // Keep the interruption visible during the next polling delay.
      state = { ...state, connectionInterrupted: true }
    }
  }
}
