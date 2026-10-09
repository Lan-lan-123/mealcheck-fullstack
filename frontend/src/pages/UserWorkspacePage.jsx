import { formatTime } from '../utils/time'
import { useWorkspaceSection } from '../hooks/useWorkspaceNavigation'
import { useDialog } from '../components/DialogProvider'
import DietGoalsPanel from './user/DietGoalsPanel'
import DietRecordsPanel from './user/DietRecordsPanel'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Camera, RefreshCw, ShieldCheck, UploadCloud, Utensils } from 'lucide-react'
import { analyzeMeal, deleteMeal, mealAnalysisRequest, updateCurrentGoal } from '../api/client'
import { resolveMealAnalysis, submitMealAnalysisWithRetry } from '../utils/mealAnalysisJob'
import { categoryName } from '../utils/mealLabels'
import MealCorrectionModal from '../components/MealCorrectionModal'
import AssistantCard from '../components/AssistantCard'
import AiBudgetNotice from '../components/AiBudgetNotice'
import Empty from '../components/Empty'
import ReportView from '../components/ReportView'
import ResultView from '../components/ResultView'
import TrendCard from '../components/TrendCard'
import WeeklyReportDetail from '../components/WeeklyReportDetail'
import WorkspaceShell from '../components/WorkspaceShell'
import DetailDrawer from '../components/DetailDrawer'
import AuthImage from '../components/AuthImage'
import useWeeklyReportJob from '../hooks/useWeeklyReportJob'
import useUserWorkspaceData from '../hooks/useUserWorkspaceData'
import useGoalDraft from '../hooks/useGoalDraft'
import { userNavigation, goals, defaultRecordFilters } from './userWorkspaceConfig'

function isAdmin(user) { return user?.role === 'ADMIN' }

export default function UserWorkspacePage({ user, onLogout, onDeleteAccount, onOpenAdmin }) {
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState('')
  const [goal, setGoal] = useState('current')
  const [result, setResult] = useState(null)
  const [recordPage, setRecordPage] = useState(0)
  const [recordSize, setRecordSize] = useState(5)
  const [recordFiltersDraft, setRecordFiltersDraft] = useState(defaultRecordFilters)
  const [recordFilters, setRecordFilters] = useState(defaultRecordFilters)
  const [loading, setLoading] = useState(false)
  const [deletingRecord, setDeletingRecord] = useState(false)
  const [savingGoal, setSavingGoal] = useState(false)
  const [message, setMessage] = useState('')
  const [showWeeklyDetail, setShowWeeklyDetail] = useState(false)
  const [showAnalysisDetail, setShowAnalysisDetail] = useState(false)
  const [viewingRecord, setViewingRecord] = useState(null)
  const [activeSection, setActiveSection] = useWorkspaceSection('user')
  const { confirm } = useDialog()
  const [correctionRecord, setCorrectionRecord] = useState(null)
  const [dataRevision, setDataRevision] = useState(0)
  const [budgetRevision, setBudgetRevision] = useState(0)
  const uploadController = useRef(null)
  const recoveryController = useRef(null)
  const submission = useRef(null)
  const [recovering, setRecovering] = useState(false)
  const pendingStorageKey = `mealcheck:analysis:${user.username}`
  const reportReceiver = useRef(null)
  const reportJob = useWeeklyReportJob(next => reportReceiver.current(next), setMessage)
  const data = useUserWorkspaceData(activeSection, { recordPage, recordSize, recordFilters, dataRevision, reportPending: !!reportJob.status })
  reportReceiver.current = data.report.setData
  const recordPageData = data.records.data, report = data.report.data, trend = data.trend.data
  const goalProfile = data.goal.data
  const goalEditor = useGoalDraft(goalProfile)
  const goalDraft = goalEditor.draft, setGoalDraft = goalEditor.edit

  useEffect(() => {
    const controller = new AbortController()
    const key = sessionStorage.getItem(pendingStorageKey)
    if (key) recoverAnalysis(key, controller.signal)
    return () => { controller.abort(); uploadController.current?.abort(); recoveryController.current?.abort() }
  }, [pendingStorageKey])

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview) }, [preview])

  async function recoverAnalysis(key, signal) {
    if (recoveryController.current) return
    const controller = new AbortController()
    recoveryController.current = controller
    const abort = () => controller.abort()
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) controller.abort()
    setRecovering(true)
    try {
      const state = await mealAnalysisRequest(key, controller.signal)
      const restored = await resolveMealAnalysis(state, { load: mealAnalysisRequest, signal: controller.signal, onUpdate: analysisProgress })
      if (controller.signal.aborted) return
      {
        reportJob.cancel()
        setResult(restored)
        sessionStorage.removeItem(pendingStorageKey)
        setDataRevision(value => value + 1)
        setMessage('已找回上次图片分析结果。')
      }
    } catch (err) {
      if (err.name !== 'AbortError') {
        setMessage(err.message)
        if ([404, 410].includes(err.status)) sessionStorage.removeItem(pendingStorageKey)
      }
    } finally {
      signal?.removeEventListener('abort', abort)
      if (recoveryController.current === controller) { recoveryController.current = null; if (!controller.signal.aborted) setRecovering(false) }
      if (!controller.signal.aborted) setBudgetRevision(value => value + 1)
    }
  }

  function analysisProgress(state) {
    setMessage(state.connectionInterrupted ? '连接暂时中断，正在自动重连查询。' : state.status === 'PENDING' ? '图片已接收，正在排队；可以刷新页面继续跟踪。' : state.status === 'PROCESSING' ? '正在识别图片并生成建议，请稍候。' : '')
  }

  function onFileChange(e) {
    const picked = e.target.files?.[0]
    submission.current = null
    setFile(picked)
    setPreview(picked ? URL.createObjectURL(picked) : '')
  }

  async function analyze() {
    if (uploadController.current || recovering) return
    if (!file) {
      setMessage('请先选择一张饮食图片。')
      return
    }
    setLoading(true)
    setMessage('正在上传图片，请稍候。')
    const controller = new AbortController()
    uploadController.current = controller
    const previous = submission.current
    const key = sessionStorage.getItem(pendingStorageKey) || (previous?.file === file && previous?.goal === goal ? previous.key : crypto.randomUUID())
    submission.current = { key, file, goal }
    sessionStorage.setItem(pendingStorageKey, key)
    try {
      const state = await submitMealAnalysisWithRetry(file, goal, key, {
        submit: analyzeMeal, signal: controller.signal,
        onRetry: ({ attempt }) => setMessage(`上传入口繁忙，正在等待后自动重试（${attempt}/2）。`)
      })
      const completed = await resolveMealAnalysis(state, { load: mealAnalysisRequest, signal: controller.signal, onUpdate: analysisProgress })
      if (controller.signal.aborted) return
      {
        reportJob.cancel()
        setResult(completed)
        sessionStorage.removeItem(pendingStorageKey)
        setRecordPage(0)
        setDataRevision(value => value + 1)
      }
    } catch (err) {
      if (err.name !== 'AbortError') setMessage(err.message)
    } finally {
      uploadController.current = null
      if (!controller.signal.aborted) setLoading(false)
      if (!controller.signal.aborted) setBudgetRevision(value => value + 1)
    }
  }

  async function handleDeleteRecord(id) {
    if (deletingRecord) return
    if (!await confirm('确认删除这条饮食记录吗？相关图片也会一起删除。', { danger: true, confirmText: '确认删除' })) return
    setDeletingRecord(true)
    setMessage('')
    try {
      await deleteMeal(id)
      reportJob.cancel()
      if (result?.recordId === id) setResult(null)
      setMessage('饮食记录已删除。')
      setDataRevision(value => value + 1)
    } catch (err) {
      setMessage(err.message || '删除饮食记录失败。')
    } finally {
      setDeletingRecord(false)
    }
  }

  function applyRecordFilters(e) {
    e.preventDefault()
    setRecordPage(0)
    setRecordFilters(recordFiltersDraft)
  }

  function resetRecordFilters() {
    setRecordFiltersDraft(defaultRecordFilters)
    setRecordFilters(defaultRecordFilters)
    setRecordPage(0)
  }

  async function saveGoal(e) {
    e.preventDefault()
    if (savingGoal) return
    setSavingGoal(true)
    setMessage('')
    const savedVersion = goalEditor.version.current
    try {
      const nextGoal = await updateCurrentGoal({
        goalType: goalDraft.goalType,
        endDate: goalDraft.endDate || null,
        note: goalDraft.note
      })
      goalEditor.committed(nextGoal, savedVersion)
      data.goal.setData(nextGoal)
      setMessage('当前目标已更新')
    } catch (err) {
      setMessage(err.message || '更新目标失败')
    } finally {
      setSavingGoal(false)
    }
  }

  const avgLabel = useMemo(() => (report ? `${report.averageScore || 0}` : '--'), [report])
  const goalLabel = goals.find(item => item.value === goalProfile?.goalType)?.label || '均衡饮食'
  const records = recordPageData.content || []

  return (
    <WorkspaceShell user={user} items={userNavigation} activeKey={activeSection} onNavigate={setActiveSection} onLogout={onLogout}
      switchAction={isAdmin(user) ? { label: '管理后台', onClick: onOpenAdmin } : undefined}
      actions={<><button className="workspace-refresh" type="button" onClick={data.retry} disabled={data.loading} aria-label="刷新当前页面"><RefreshCw size={16} className={data.loading ? 'is-spinning' : ''} />刷新</button>{!isAdmin(user) && <button className="account-delete-button" onClick={onDeleteAccount} type="button">注销账号</button>}</>}>

      {message && <div className="toast" role="status">{message}</div>}

      <section className="dashboard-page">
        {data.loading && <p role="status">正在加载当前页面...</p>}
        {data.error && <div className="admin-message error" role="alert">{data.error}<button className="secondary-btn" type="button" onClick={data.retry}>重试</button></div>}
        {activeSection === 'overview' && <>
            <section className="analysis-layout">
              <div className="card upload-card">
                <h2>
                  <Camera size={20} />
                  上传饮食图片
                </h2>
                <p>选择餐盘图片后，系统会识别食物、计算结构评分，并结合知识库生成建议。</p>
                <AiBudgetNotice revision={budgetRevision} />
                <label className="file-picker">
                  <input type="file" aria-label="选择饮食图片" accept="image/*" onChange={onFileChange} disabled={loading || recovering} />
                  {preview ? <img src={preview} alt="待分析的饮食图片" /> : <span className="upload-placeholder"><span className="upload-placeholder-icon"><UploadCloud size={30} strokeWidth={1.5} /></span><strong>选择图片</strong><small>上传清晰的餐盘照片，开始营养分析</small></span>}
                </label>
                <div className="upload-actions">
                  <select aria-label="本次分析目标" value={goal} onChange={e => setGoal(e.target.value)} disabled={loading}>
                    {goals.map(g => (
                      <option key={g.value} value={g.value}>
                        {g.label}
                      </option>
                    ))}
                  </select>
                  <button className="primary" onClick={analyze} disabled={loading || recovering}>
                    {loading ? '分析中...' : '开始分析'}
                  </button>
                  {sessionStorage.getItem(pendingStorageKey) && <button type="button" disabled={recovering || loading}
                    onClick={() => recoverAnalysis(sessionStorage.getItem(pendingStorageKey))}>查询上次分析结果</button>}
                  {sessionStorage.getItem(pendingStorageKey) && <button type="button" disabled={recovering || loading} onClick={async () => {
                    if (await confirm('上次请求可能仍在处理。确认另建一次分析吗？')) {
                      sessionStorage.removeItem(pendingStorageKey); submission.current = null; setMessage('已准备新的分析，请点击开始分析。')
                    }
                  }}>另建一次分析</button>}
                </div>
                <div className="goal-quick-link"><span>当前目标 · <strong>{goalLabel}</strong></span><button type="button" onClick={() => setActiveSection('goals')}>管理目标</button></div>
              </div>

              <div className="card score-card">
                <h2>
                  <ShieldCheck size={20} />
                  本次分析结果
                </h2>
                {result ? <><ResultView result={result} compact onViewDetails={() => setShowAnalysisDetail(true)} /><button className="secondary-btn correct-result-button" type="button" onClick={() => setCorrectionRecord({ id: result.recordId, foods: result.recognition?.foods, revision: result.revision || 0 })}>纠正识别结果</button></> : <div className="analysis-empty"><span className="analysis-empty-icon"><Utensils size={34} strokeWidth={1.3} /></span><h3>这一餐，吃得怎么样？</h3><Empty text="上传图片后，这里会显示评分、食物识别、风险标签和饮食建议。" /><div className="analysis-steps"><span>01 选择图片</span><span>02 智能分析</span><span>03 获取建议</span></div></div>}
              </div>
            </section>
            </>}

        {activeSection === 'trends' && (trend ? <TrendCard trend={trend} /> : <div className="card"><h2>饮食趋势</h2><Empty text="暂无趋势数据，记录饮食后可在这里查看变化。" /></div>)}
        {activeSection === 'reports' && (showWeeklyDetail && report
          ? <WeeklyReportDetail key={[report.periodStart, report.periodEnd, dataRevision].join(':')} report={report} avgLabel={avgLabel} onClose={() => setShowWeeklyDetail(false)} />
          : report ? <ReportView report={report} avgLabel={avgLabel} onOpenDetail={() => setShowWeeklyDetail(true)} onGenerate={reportJob.generate} jobStatus={reportJob.status} />
          : <div className="card report-empty"><h2>本周饮食概览</h2><Empty text="暂无周报数据，记录饮食后可以生成专属周报。" /><button className="secondary-btn" type="button" onClick={reportJob.generate} disabled={!!reportJob.status}>{reportJob.status ? '周报生成中…' : '生成今日周报 / 查看进度'}</button></div>)}
        {activeSection === 'goals' && <DietGoalsPanel {...{ goalLabel, goalDraft, setGoalDraft, saveGoal }} loading={savingGoal} />}
        {activeSection === 'records' && <DietRecordsPanel {...{ recordPageData, recordFiltersDraft, setRecordFiltersDraft, applyRecordFilters, resetRecordFilters, records, handleDeleteRecord, setCorrectionRecord, setViewingRecord, recordSize, setRecordPage, setRecordSize }} loading={deletingRecord} recordsLoading={data.records.loading} />}

        <AssistantCard onError={setMessage} />
      </section>

      {showAnalysisDetail && result && <DetailDrawer title="完整餐食分析" onClose={() => setShowAnalysisDetail(false)}><ResultView result={result} /></DetailDrawer>}
      {viewingRecord && <DetailDrawer title="餐食记录详情" onClose={() => setViewingRecord(null)}>
        <p className="hint">{formatTime(viewingRecord.createdAt)} · {goals.find(item => item.value === viewingRecord.goal)?.label || '饮食记录'}</p>
        {viewingRecord.imageUrl && <div className="record-detail-image"><AuthImage url={viewingRecord.imageUrl} alt="本次餐食图片" /></div>}
        <ResultView result={{ evaluation: { score: viewingRecord.score, summary: viewingRecord.summary, riskTags: viewingRecord.riskTags }, recognition: { foods: viewingRecord.foods }, advice: viewingRecord.advice }} />
        {!!Object.keys(viewingRecord.categoryCounts || {}).length && <><h3>食物分类统计</h3><div className="chips">{Object.entries(viewingRecord.categoryCounts).map(([key, count]) => <span key={key}>{categoryName(key)} · {count}</span>)}</div></>}
      </DetailDrawer>}
      {correctionRecord && <MealCorrectionModal record={correctionRecord} onClose={() => setCorrectionRecord(null)} onSaved={next => {
        reportJob.cancel()
        if (result?.recordId === next.recordId) setResult(next)
        setCorrectionRecord(null)
        setDataRevision(value => value + 1)
        setMessage('识别已纠正，评分和画像已更新，旧周报已失效。')
      }} />}
    </WorkspaceShell>
  )
}
