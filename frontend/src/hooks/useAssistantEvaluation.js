import { useEffect, useRef, useState } from 'react'
import { useDialog } from '../components/DialogProvider'
import { benchmarkDiagnostics } from '../pages/admin/evaluationLabels'
import { adminAddAssistantEvaluationCandidate, adminAssistantEvaluationCandidates, adminAssistantEvaluationHistory, adminAssistantEvaluationHistoryDetail, adminAssistantEvaluationReviews, adminAssistantEvaluationStatus, adminCompareAssistantEvaluations, adminReviewAssistantEvaluationCase, adminStartAssistantEvaluation, adminUpdateAssistantEvaluationCandidateStatus } from '../api/client'

const defaultEvaluationCandidate = {
  question: '', observedAnswer: '', issueType: 'ANSWER_QUALITY', sanitizedContext: '',
  expectedSkill: '', expectedTools: '', notes: '', sourceReference: '', sanitizedConfirmed: false
}

export default function useAssistantEvaluation(enabled, refreshRevision) {
  const { confirm, prompt } = useDialog()
  const selection = useRef(0)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [assistantBenchmark, setAssistantBenchmark] = useState(null)
  const [assistantBenchmarkHistory, setAssistantBenchmarkHistory] = useState([])
  const [assistantBenchmarkLoading, setAssistantBenchmarkLoading] = useState(false)
  const [assistantReviewReport, setAssistantReviewReport] = useState(null)
  const [reviewingCaseId, setReviewingCaseId] = useState(null)
  const [assistantComparison, setAssistantComparison] = useState(null)
  const [comparisonBaseRunId, setComparisonBaseRunId] = useState('')
  const [comparisonCandidateRunId, setComparisonCandidateRunId] = useState('')
  const [evaluationCandidates, setEvaluationCandidates] = useState([])
  const [evaluationCandidateForm, setEvaluationCandidateForm] = useState(defaultEvaluationCandidate)
  const [evaluationCandidateLoading, setEvaluationCandidateLoading] = useState(false)

  useEffect(() => {
    if (!enabled) return undefined
    setError('')
    const selected = selection.current
    let cancelled = false
    const load = (request, apply) => request().then(result => {
      if (!cancelled) apply(result)
    }).catch(err => { if (!cancelled) setError(err.message || '加载助手评测数据失败') })
    load(adminAssistantEvaluationStatus, result => { if (selected === selection.current) setAssistantBenchmark(result) })
    load(adminAssistantEvaluationCandidates, result => setEvaluationCandidates(result?.candidates || []))
    load(adminAssistantEvaluationHistory, history => {
      const runs = history?.runs || []
      setAssistantBenchmarkHistory(runs)
      const comparableRuns = runs.filter(item => item.status === 'COMPLETED')
      if (comparableRuns.length >= 2) {
        setComparisonCandidateRunId(current => current || comparableRuns[0].runId)
        setComparisonBaseRunId(current => current || comparableRuns[1].runId)
      }
    })
    return () => {
      cancelled = true
    }
  }, [enabled, refreshRevision])

  useEffect(() => {
    if (!enabled || !['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)) return undefined
    let active = true
    let timer
    const controller = new AbortController()
    const runId = assistantBenchmark.runId
    async function poll() {
      try {
        const result = await adminAssistantEvaluationHistoryDetail(runId, controller.signal)
        if (!active) return
        setAssistantBenchmark(result)
        if (['PENDING', 'RUNNING'].includes(result.status)) timer = window.setTimeout(poll, 2000)
        else {
          const history = await adminAssistantEvaluationHistory()
          if (active) setAssistantBenchmarkHistory(history?.runs || [])
        }
      } catch (err) {
        if (!active) return
        setError(err.message || '刷新助手评测状态失败')
        timer = window.setTimeout(poll, 2000)
      }
    }
    timer = window.setTimeout(poll, 2000)
    return () => { active = false; controller.abort(); window.clearTimeout(timer) }
  }, [enabled, assistantBenchmark?.runId, assistantBenchmark?.status])

  useEffect(() => {
    if (!enabled) return undefined
    const runId = assistantBenchmark?.runId
    if (!runId || ['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)) {
      setAssistantReviewReport(null)
      return undefined
    }
    let cancelled = false
    adminAssistantEvaluationReviews(runId)
      .then(result => {
        if (!cancelled) setAssistantReviewReport(result)
      })
      .catch(err => {
        if (!cancelled) setError(err.message || '加载人工复核记录失败')
      })
    return () => {
      cancelled = true
    }
  }, [enabled, assistantBenchmark?.runId, assistantBenchmark?.status])

  async function handleRunAssistantEvaluation(mode) {
    if (assistantBenchmarkLoading || ['PENDING', 'RUNNING'].includes(assistantBenchmark?.status)) return
    const modeCopy = {
      QUICK: '快速测试包含 10 条代表性用例',
      STANDARD: '标准测试包含 50 条核心用例',
      FULL: '完整测试包含 200 条分层用例，耗时和模型费用较高'
    }
    const confirmed = await confirm(
      `${modeCopy[mode] || '本次测试'}，将运行生产 Function Calling 链路并调用裁判模型；模型请求数会随工具轮数增加。确认开始吗？`, { title: '启动助手评测', confirmText: '开始评测' })
    if (!confirmed) return
    const selected = ++selection.current
    setAssistantBenchmarkLoading(true)
    setMessage('')
    setError('')
    try {
      const result = await adminStartAssistantEvaluation(mode)
      if (selected !== selection.current) return
      setAssistantBenchmark(result)
      setAssistantReviewReport(null)
      adminAssistantEvaluationHistory()
        .then(history => setAssistantBenchmarkHistory(history?.runs || []))
        .catch(err => setError(err.message || '刷新助手评测历史失败'))
      setMessage(['PENDING', 'RUNNING'].includes(result.status) ? '助手评测已在后台启动' : (result.message || '助手评测状态已更新'))
    } catch (err) {
      setError(err.message || '启动助手评测失败')
    } finally {
      setAssistantBenchmarkLoading(false)
    }
  }

  async function handleViewAssistantEvaluation(runId) {
    const selected = ++selection.current
    setAssistantBenchmarkLoading(true)
    setError('')
    try {
      const result = await adminAssistantEvaluationHistoryDetail(runId)
      if (selected !== selection.current) return
      setAssistantBenchmark(result)
    } catch (err) {
      setError(err.message || '加载助手评测历史明细失败')
    } finally {
      setAssistantBenchmarkLoading(false)
    }
  }

  async function handleReviewAssistantCase(item, verdict) {
    if (!assistantBenchmark?.runId) return
    const existing = (assistantReviewReport?.reviews || []).find(review => review.caseId === item.id)
    const notes = await prompt(
      verdict === 'PASSED' ? '人工复核：通过。可填写备注：' : '人工复核：不通过。请填写主要问题：',
      { defaultValue: existing?.notes || '', multiline: true, required: verdict !== 'PASSED', inputLabel: '复核备注', title: '人工复核' })
    if (notes === null) return
    setReviewingCaseId(item.id)
    setError('')
    try {
      const result = await adminReviewAssistantEvaluationCase(
        assistantBenchmark.runId, item.id, { verdict, notes })
      setAssistantReviewReport(result)
      setMessage(`已保存 ${item.id} 的人工复核结论`)
    } catch (err) {
      setError(err.message || '保存人工复核失败')
    } finally {
      setReviewingCaseId(null)
    }
  }

  async function handleCompareAssistantRuns() {
    if (!comparisonBaseRunId || !comparisonCandidateRunId) return
    setAssistantBenchmarkLoading(true)
    setError('')
    try {
      setAssistantComparison(await adminCompareAssistantEvaluations(
        comparisonBaseRunId, comparisonCandidateRunId))
    } catch (err) {
      setError(err.message || '对比评测历史失败')
    } finally {
      setAssistantBenchmarkLoading(false)
    }
  }

  async function handleAddEvaluationCandidate(event) {
    event.preventDefault()
    setEvaluationCandidateLoading(true)
    setError('')
    try {
      await adminAddAssistantEvaluationCandidate({
        ...evaluationCandidateForm,
        expectedTools: evaluationCandidateForm.expectedTools
          .split(',').map(item => item.trim()).filter(Boolean)
      })
      const result = await adminAssistantEvaluationCandidates()
      setEvaluationCandidates(result?.candidates || [])
      setEvaluationCandidateForm(defaultEvaluationCandidate)
      setMessage('失败样本已进入候选库，审核通过后仍需人工编写正式评测期望')
    } catch (err) {
      setError(err.message || '添加失败样本候选失败')
    } finally {
      setEvaluationCandidateLoading(false)
    }
  }

  async function handleEvaluationCandidateStatus(id, status) {
    setEvaluationCandidateLoading(true)
    setError('')
    try {
      await adminUpdateAssistantEvaluationCandidateStatus(id, status)
      const result = await adminAssistantEvaluationCandidates()
      setEvaluationCandidates(result?.candidates || [])
      setMessage(status === 'APPROVED' ? '候选样本已通过审核' : '候选样本已拒绝')
    } catch (err) {
      setError(err.message || '更新候选样本状态失败')
    } finally {
      setEvaluationCandidateLoading(false)
    }
  }

  const assistantDiagnostics = benchmarkDiagnostics(assistantBenchmark)
  const assistantReviewsByCase = new Map(
    (assistantReviewReport?.reviews || []).map(item => [item.caseId, item]))

  return {
    assistantBenchmark, assistantBenchmarkHistory, assistantBenchmarkLoading, assistantReviewReport,
    reviewingCaseId, assistantComparison, comparisonBaseRunId, setComparisonBaseRunId,
    comparisonCandidateRunId, setComparisonCandidateRunId, evaluationCandidates, evaluationCandidateForm,
    setEvaluationCandidateForm, evaluationCandidateLoading, handleRunAssistantEvaluation, handleViewAssistantEvaluation,
    handleReviewAssistantCase, handleCompareAssistantRuns, handleAddEvaluationCandidate, handleEvaluationCandidateStatus,
    assistantDiagnostics, assistantReviewsByCase, error, message
  }
}
