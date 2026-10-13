import { useEffect, useRef, useState } from 'react'
import { requestWeeklyReport, weeklyReportJob } from '../api/client'
import { resolveWeeklyReportJob } from '../utils/weeklyReportJob'

export default function useWeeklyReportJob(onReport, onMessage) {
  const controller = useRef(null)
  const [status, setStatus] = useState('')
  const [progress, setProgress] = useState(null)
  function cancel() {
    controller.current?.abort()
    controller.current = null
    setStatus('')
    setProgress(null)
  }
  useEffect(() => () => controller.current?.abort(), [])

  async function generate() {
    if (controller.current) return
    const active = new AbortController()
    controller.current = active
    setStatus('SUBMITTING')
    setProgress({ status: 'SUBMITTING', startedAt: Date.now() })
    onMessage('')
    try {
      const job = await requestWeeklyReport(7, active.signal)
      const report = await resolveWeeklyReportJob(job, {
        load: weeklyReportJob, signal: active.signal, onUpdate: next => {
          if (active.signal.aborted) return
          setStatus(next.status)
          setProgress(task => ({ ...task, status: next.status }))
        }
      })
      if (active.signal.aborted) return
      onReport(report)
      setProgress(task => ({ ...task, status: 'SUCCEEDED', finishedAt: Date.now() }))
      onMessage('周报已生成。同一天重复请求会复用这份报告。')
    } catch (error) {
      if (!active.signal.aborted && error.name !== 'AbortError') {
        onMessage(error.message)
        setProgress(task => ({ ...task, status: 'INTERRUPTED', message: error.message, finishedAt: Date.now() }))
      }
    } finally {
      if (!active.signal.aborted) setStatus('')
      if (controller.current === active) controller.current = null
    }
  }

  return { generate, status, progress, cancel }
}
