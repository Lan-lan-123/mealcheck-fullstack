import { useEffect, useRef, useState } from 'react'
import { requestWeeklyReport, weeklyReportJob } from '../api/client'
import { resolveWeeklyReportJob } from '../utils/weeklyReportJob'

export default function useWeeklyReportJob(onReport, onMessage) {
  const controller = useRef(null)
  const [status, setStatus] = useState('')
  function cancel() {
    controller.current?.abort()
    controller.current = null
    setStatus('')
  }
  useEffect(() => () => controller.current?.abort(), [])

  async function generate() {
    if (controller.current) return
    const active = new AbortController()
    controller.current = active
    setStatus('PENDING')
    onMessage('')
    try {
      const job = await requestWeeklyReport(7, active.signal)
      const report = await resolveWeeklyReportJob(job, {
        load: weeklyReportJob, signal: active.signal, onUpdate: next => setStatus(next.status)
      })
      if (active.signal.aborted) return
      onReport(report)
      onMessage('周报已生成。同一天重复请求会复用这份报告。')
    } catch (error) {
      if (error.name !== 'AbortError') onMessage(error.message)
    } finally {
      if (!active.signal.aborted) setStatus('')
      if (controller.current === active) controller.current = null
    }
  }

  return { generate, status, cancel }
}
