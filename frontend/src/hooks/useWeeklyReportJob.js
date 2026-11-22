// @ts-check
import { useEffect, useRef, useState } from 'react'
import { requestWeeklyReport, weeklyReportJob } from '../api/client'
import { resolveWeeklyReportJob } from '../utils/weeklyReportJob'

/** @param {(report: import('../api/contracts').WeeklyReport) => void} onReport @param {(message: string) => void} onMessage */
export default function useWeeklyReportJob(onReport, onMessage) {
  const controller = useRef(/** @type {AbortController | null} */ (null))
  const [status, setStatus] = useState('')
  const [progress, setProgress] = useState(/** @type {import('../api/contracts').ProgressTask | null} */ (null))
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
          setProgress(task => ({ ...task, status: next.status, connectionInterrupted: next.connectionInterrupted }))
        }
      })
      if (active.signal.aborted) return
      onReport(report)
      setProgress(task => ({ ...task, status: 'SUCCEEDED', connectionInterrupted: false, finishedAt: Date.now() }))
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
