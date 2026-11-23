// @ts-check
import { pollTask } from './taskPolling.js'
export { waitForPoll } from './taskPolling.js'

/** @param {import('../api/contracts').WeeklyReportJob} initial @param {import('../api/contracts').PollOptions<import('../api/contracts').WeeklyReportJob>} options */
export async function resolveWeeklyReportJob(initial, options) {
  return pollTask(initial, initial.id, {
    maxPolls: 200, maxDurationMs: 10 * 60 * 1000, ...options,
    timeoutMessage: '周报仍在后台处理中，请稍后点击查看生成进度。',
    evaluate: /** @type {(job: import('../api/contracts').WeeklyReportJob) => {done: false} | {done: true, result: import('../api/contracts').WeeklyReport}} */ (job => {
      if (job.status === 'SUCCEEDED') {
        if (!job.report) throw new Error('周报结果暂时不可用，请稍后重试。')
        return { done: true, result: job.report }
      }
      if (job.status === 'DEAD') throw new Error('周报生成失败，已停止自动重试。请稍后联系管理员或明日重试。')
      if (job.status === 'STALE') throw new Error('餐食记录已变化，旧周报已失效，请重新生成。')
      if (!['PENDING', 'RUNNING', 'RETRY'].includes(job.status)) throw new Error('无法识别周报任务状态。')
      return { done: false }
    })
  })
}
