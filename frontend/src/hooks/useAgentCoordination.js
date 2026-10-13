import { useEffect, useRef, useState } from 'react'
import { createRequestScope } from '../utils/requestScope'
import { emptyPage, normalizePage } from '../utils/pageData'
import { adminWeeklyReportRunDetail, adminWeeklyReportRuns } from '../api/client'

export const defaultCoordinationFilters = { status: 'ALL', userId: '', jobId: '' }

export default function useAgentCoordination(enabled, refreshRevision) {
  const [error, setError] = useState('')
  const coordinationScope = useRef(createRequestScope())
  const detailScope = useRef(createRequestScope())
  const selectedRun = useRef(null)
  useEffect(() => () => { coordinationScope.current.invalidate(); detailScope.current.invalidate() }, [])
  const [coordinationPageData, setCoordinationPageData] = useState(emptyPage())
  const [coordinationDetail, setCoordinationDetail] = useState(null)
  const [coordinationTrigger, setCoordinationTrigger] = useState(null)
  const [coordinationLoading, setCoordinationLoading] = useState(false)
  const [coordinationPage, setCoordinationPage] = useState(0)
  const [coordinationSize, setCoordinationSize] = useState(20)
  const initialFilters = () => {
    const jobId = new URLSearchParams(window.location.search).get('jobId') || ''
    return { ...defaultCoordinationFilters, jobId: /^\d+$/.test(jobId) ? jobId : '' }
  }
  const [coordinationFiltersDraft, setCoordinationFiltersDraft] = useState(initialFilters)
  const [coordinationFilters, setCoordinationFilters] = useState(initialFilters)

  const coordinationRuns = coordinationPageData.content || []
  const hasRunningCoordination = coordinationRuns.some(run => run.status === 'RUNNING')
  async function loadCoordinationRuns() {
    const current = coordinationScope.current.begin()
    setCoordinationLoading(true)
    setError('')
    try {
      const data = await adminWeeklyReportRuns({
        page: coordinationPage,
        size: coordinationSize,
        ...coordinationFilters
      })
      if (current()) setCoordinationPageData(normalizePage(data, coordinationSize))
    } catch (err) {
      if (current()) setError(err.message || '加载 Agent 协调运行记录失败')
    } finally {
      if (current()) setCoordinationLoading(false)
    }
  }

  useEffect(() => {
    if (!enabled) return
    loadCoordinationRuns()
    return () => { coordinationScope.current.invalidate(); detailScope.current.invalidate(); selectedRun.current = null; setCoordinationLoading(false) }
  }, [enabled, refreshRevision, coordinationPage, coordinationSize, coordinationFilters])

  useEffect(() => {
    if (!enabled || !hasRunningCoordination) return undefined
    const timer = window.setInterval(loadCoordinationRuns, 5000)
    return () => window.clearInterval(timer)
  }, [enabled, hasRunningCoordination, coordinationPage, coordinationSize, coordinationFilters])

  useEffect(() => {
    if (!enabled) return undefined
    const runId = coordinationDetail?.run?.runId
    if (!runId || coordinationDetail?.run?.status !== 'RUNNING') return undefined
    let active = true
    const timer = window.setInterval(() => {
      adminWeeklyReportRunDetail(runId)
        .then(result => { if (active && selectedRun.current === runId) setCoordinationDetail(result) })
        .catch(err => { if (active && selectedRun.current === runId) setError(err.message || '刷新 Agent 协调详情失败') })
    }, 3000)
    return () => { active = false; window.clearInterval(timer) }
  }, [enabled, coordinationDetail?.run?.runId, coordinationDetail?.run?.status])

  async function openCoordinationRun(runId, trigger) {
    setCoordinationTrigger(trigger)
    const current = detailScope.current.begin()
    selectedRun.current = runId
    setCoordinationLoading(true)
    setError('')
    try {
      const detail = await adminWeeklyReportRunDetail(runId)
      if (current()) setCoordinationDetail(detail)
    } catch (err) {
      if (current()) setError(err.message || '加载 Agent 协调详情失败')
    } finally {
      if (current()) setCoordinationLoading(false)
    }
  }

  function applyCoordinationFilters(event) {
    event.preventDefault()
    setCoordinationPage(0)
    setCoordinationFilters(coordinationFiltersDraft)
  }

  function closeCoordinationRun() { detailScope.current.invalidate(); selectedRun.current = null; setCoordinationDetail(null) }
  return {
    coordinationPageData, coordinationDetail, coordinationTrigger, coordinationLoading,
    coordinationRuns, coordinationSize, setCoordinationSize, setCoordinationPage,
    coordinationFiltersDraft, setCoordinationFiltersDraft, setCoordinationFilters, loadCoordinationRuns,
    openCoordinationRun, applyCoordinationFilters, closeCoordinationRun, error
  }
}
