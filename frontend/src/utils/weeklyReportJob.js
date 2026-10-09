export function waitForPoll(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      reject(new DOMException('Request cancelled', 'AbortError'))
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort)
      resolve()
    }, milliseconds)
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) abort()
  })
}

export async function resolveWeeklyReportJob(initial, {
  load, signal, onUpdate = () => {}, wait = waitForPoll, maxPolls = 200
}) {
  let job = initial
  for (let poll = 0; ; poll++) {
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
    onUpdate(job)
    if (job.status === 'SUCCEEDED') {
      if (!job.report) throw new Error('周报结果暂时不可用，请稍后重试。')
      return job.report
    }
    if (job.status === 'DEAD') throw new Error('周报生成失败，已停止自动重试。请稍后联系管理员或明日重试。')
    if (job.status === 'STALE') throw new Error('餐食记录已变化，旧周报已失效，请重新生成。')
    if (!['PENDING', 'RUNNING', 'RETRY'].includes(job.status)) throw new Error('无法识别周报任务状态。')
    if (poll >= maxPolls) throw new Error('周报仍在后台处理中，请稍后点击查看生成进度。')
    await wait(3000, signal)
    job = await load(job.id, signal)
  }
}
