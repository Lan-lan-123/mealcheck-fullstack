// @ts-check
import Button from '../components/Button'
import WeeklyReportHistoryPanel from '../components/WeeklyReportHistoryPanel'
import { publishDietChange } from '../utils/dietDataChanges'
import MealJournalModal from "../components/MealJournalModal"
import MealCoveragePanel from "../components/MealCoveragePanel"
import BehaviorGoalsPanel from "../components/BehaviorGoalsPanel"
import ActionPlansPanel from "../components/ActionPlansPanel"
import { mealTypes } from "../utils/time"
import { formatTime } from '../utils/time'
import { useWorkspaceSection } from '../hooks/useWorkspaceNavigation'
import { useDialog } from '../components/DialogProvider'
import DietGoalsPanel from './user/DietGoalsPanel'
import DietRecordsPanel from './user/DietRecordsPanel'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Camera, RefreshCw, ShieldCheck, UploadCloud, Utensils } from 'lucide-react'
import { analyzeMeal, deleteMeal, mealAnalysisRequest, updateCurrentGoal } from '../api/client'
import { resolveMealAnalysis, submitMealAnalysisWithRetry } from '../utils/mealAnalysisJob'
import { analysisFailure } from '../utils/recognitionExplanation'
import { categoryName } from '../utils/mealLabels'
import MealCorrectionModal from '../components/MealCorrectionModal'
import AssistantCard from '../components/AssistantCard'
import AiBudgetNotice from '../components/AiBudgetNotice'
import Empty from '../components/Empty'
import ReportView from '../components/ReportView'
import { MealAnalysisProgress, WeeklyReportProgress } from '../components/TaskProgress'
import ResultView from '../components/ResultView'
import TrendCard from '../components/TrendCard'
import WeeklyReportDetail from '../components/WeeklyReportDetail'
import WorkspaceShell from '../components/WorkspaceShell'
import DetailDrawer from '../components/DetailDrawer'
import AuthImage from '../components/AuthImage'
import MealRecordLoader from '../components/MealRecordLoader'
import DietPreferencesPanel from '../components/DietPreferencesPanel'
import TodayMealsPanel from '../components/TodayMealsPanel'
import NextMealPanel from '../components/NextMealPanel'
import useWeeklyReportJob from '../hooks/useWeeklyReportJob'
import useUserWorkspaceData from '../hooks/useUserWorkspaceData'
import useGoalDraft from '../hooks/useGoalDraft'
import { userNavigation, goals, defaultRecordFilters } from './userWorkspaceConfig'

/** @param {import('../api/contracts').SessionUser} user */
function isAdmin(user) { return user?.role === 'ADMIN' }

/** @param {{user: import('../api/contracts').SessionUser; onLogout: () => void; onDeleteAccount: () => void; onOpenAdmin: () => void}} props */
export default function UserWorkspacePage({ user, onLogout, onDeleteAccount, onOpenAdmin }) {
  const [file, setFile] = useState(/** @type {File | null} */ (null))
  const [preview, setPreview] = useState('')
  const [goal, setGoal] = useState('current')
  const [result, setResult] = useState(/** @type {import('../api/contracts').MealAnalysisResult | null} */ (null))
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
  const [viewingRecord, setViewingRecord] = useState(/** @type {import('../api/contracts').MealRecord | null} */ (null))
  const [recordRequest, setRecordRequest] = useState(/** @type {{id: number; action: 'edit' | 'correct' | 'view'} | null} */ (null))
  const [activeSection, setActiveSection] = useWorkspaceSection('user')
  const { confirm } = useDialog()
  const [journalEditor, setJournalEditor] = useState(/** @type {{record?: import('../api/contracts').MealRecord} | null} */ (null))
  const [correctionRecord, setCorrectionRecord] = useState(/** @type {Pick<import('../api/contracts').MealRecord, 'id' | 'foods' | 'revision' | 'uncertainFoods'> | null} */ (null))
  const [dataRevision, setDataRevision] = useState(0)
  const [budgetRevision, setBudgetRevision] = useState(0)
  const uploadController = useRef(/** @type {AbortController | null} */ (null))
  const fileInput = useRef(/** @type {HTMLInputElement | null} */ (null))
  const recoveryController = useRef(/** @type {AbortController | null} */ (null))
  const submission = useRef(/** @type {{key: string; file: File; goal: string} | null} */ (null))
  const [recovering, setRecovering] = useState(false)
  const [analysisTask, setAnalysisTask] = useState(/** @type {import('../api/contracts').ProgressTask | null} */ (null))
  const pendingStorageKey = `mealcheck:analysis:${user.username}`
  const [pendingKey, setPendingKey] = useState(/** @type {string | null} */ (null))
  const [storageWarning, setStorageWarning] = useState('')
  const reportReceiver = useRef(/** @type {((report: import('../api/contracts').WeeklyReport) => void) | null} */ (null))
  const reportJob = useWeeklyReportJob(next => reportReceiver.current?.(next), setMessage)
  const data = useUserWorkspaceData(activeSection, { recordPage, recordSize, recordFilters, dataRevision, reportPending: !!reportJob.status })
  reportReceiver.current = next => { data.report.setData(next); publishDietChange('reports') }
  const recordPageData = data.records.data || { content: [], page: 0, size: recordSize, totalElements: 0, totalPages: 0 }, report = data.report.data, trend = data.trend.data
  const goalProfile = data.goal.data
  const goalEditor = useGoalDraft(goalProfile)
  const goalDraft = goalEditor.draft, setGoalDraft = goalEditor.edit

  useEffect(() => {
    const controller = new AbortController()
    let key = null
    setStorageWarning('')
    submission.current = null
    try { key = sessionStorage.getItem(pendingStorageKey) }
    catch { setStorageWarning('浏览器存储不可用，刷新后可能无法自动恢复分析，请保留当前页面。') }
    setPendingKey(key)
    if (key) recoverAnalysis(key, controller.signal)
    return () => { controller.abort(); uploadController.current?.abort(); recoveryController.current?.abort() }
  }, [pendingStorageKey])

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview) }, [preview])

  /** Keep the current page usable even when browser persistence is unavailable.
   * @param {string | null} key */
  function rememberAnalysis(key) {
    setPendingKey(key)
    try {
      if (key) sessionStorage.setItem(pendingStorageKey, key)
      else {
        try { sessionStorage.removeItem(pendingStorageKey) }
        catch { sessionStorage.setItem(pendingStorageKey, '') }
      }
    } catch {
      setStorageWarning(key
        ? '浏览器存储不可用，刷新后可能无法自动恢复分析，请保留当前页面。'
        : '浏览器未能清理上次分析状态，刷新后可能再次查询；已完成的结果可在饮食记录中查看。')
    }
  }

  /** @param {string | null} key @param {AbortSignal} [signal] */
  async function recoverAnalysis(key, signal) {
    if (!key || recoveryController.current) return
    const controller = new AbortController()
    recoveryController.current = controller
    const abort = () => controller.abort()
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) controller.abort()
    setRecovering(true)
    setMessage('')
    setAnalysisTask({ status: 'CHECKING', startedAt: Date.now() })
    try {
      const state = await mealAnalysisRequest(key, controller.signal)
      const restored = await resolveMealAnalysis(state, { load: mealAnalysisRequest, signal: controller.signal, onUpdate: analysisProgress })
      if (controller.signal.aborted) return
      {
        reportJob.cancel()
        setResult(restored)
        setAnalysisTask(task => ({ ...task, status: 'SUCCEEDED', connectionInterrupted: false, message: '', finishedAt: Date.now() }))
        rememberAnalysis(null)
        setDataRevision(value => value + 1)
        setMessage('已找回上次图片分析结果。')
      }
    } catch (err) {
      if (err.name !== 'AbortError') {
        setAnalysisTask(task => ({ ...task, status: 'INTERRUPTED', errorCode: err.code, connectionInterrupted: false, message: err.message, finishedAt: Date.now() }))
        if ([404, 410].includes(err.status)) rememberAnalysis(null)
      }
    } finally {
      signal?.removeEventListener('abort', abort)
      if (recoveryController.current === controller) { recoveryController.current = null; if (!controller.signal.aborted) setRecovering(false) }
      if (!controller.signal.aborted) setBudgetRevision(value => value + 1)
    }
  }

  /** @param {import('../api/contracts').MealAnalysisState} state */
  function analysisProgress(state) {
    if (!['PENDING', 'PROCESSING'].includes(state.status)) return
    setAnalysisTask(task => ({ ...task, status: state.status, stage: state.stage, message: state.message || '', connectionInterrupted: !!state.connectionInterrupted }))
  }

  /** @param {import('react').ChangeEvent<HTMLInputElement>} e */
  function onFileChange(e) {
    const picked = e.target.files?.[0] || null
    submission.current = null
    setFile(picked)
    setAnalysisTask(null)
    setPreview(picked ? URL.createObjectURL(picked) : '')
  }

  async function analyze() {
    if (uploadController.current || recovering) return
    if (!file) {
      setMessage('请先选择一张饮食图片。')
      return
    }
    setLoading(true)
    setMessage('')
    setResult(null)
    setAnalysisTask({ status: 'UPLOADING', startedAt: Date.now() })
    const controller = new AbortController()
    uploadController.current = controller
    try {
      const previous = submission.current
      // Persisted keys recover an earlier task; only this exact draft may resubmit it.
      const key = previous?.file === file && previous?.goal === goal ? previous.key : crypto.randomUUID()
      submission.current = { key, file, goal }
      rememberAnalysis(key)
      const state = await submitMealAnalysisWithRetry(file, goal, key, {
        submit: analyzeMeal, signal: controller.signal,
        onRetry: ({ attempt }) => setAnalysisTask(task => ({ ...task, status: 'UPLOAD_RETRY', message: `正在等待后自动重试（${attempt}/2），请保留当前图片。` }))
      })
      const completed = await resolveMealAnalysis(state, { load: mealAnalysisRequest, signal: controller.signal, onUpdate: analysisProgress })
      if (controller.signal.aborted) return
      {
        reportJob.cancel()
        setResult(completed)
        setAnalysisTask(task => ({ ...task, status: 'SUCCEEDED', connectionInterrupted: false, message: '', finishedAt: Date.now() }))
        rememberAnalysis(null)
        setRecordPage(0)
        setDataRevision(value => value + 1)
      }
    } catch (err) {
      if (err.name !== 'AbortError') {
        setAnalysisTask(task => ({ ...task, status: 'INTERRUPTED', errorCode: err.code, connectionInterrupted: false, message: err.message, finishedAt: Date.now() }))
      }
    } finally {
      uploadController.current = null
      if (!controller.signal.aborted) setLoading(false)
      if (!controller.signal.aborted) setBudgetRevision(value => value + 1)
    }
  }

  /** @param {number} id */
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

  /** @param {import('react').FormEvent} e */
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

  /** @param {import('react').FormEvent} e */
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
      actions={<><Button variant="plain" className="workspace-refresh" type="button" onClick={data.retry} disabled={data.loading} aria-label="刷新当前页面"><RefreshCw size={16} className={data.loading ? 'is-spinning' : ''} />刷新</Button>{!isAdmin(user) && <Button variant="danger" className="account-delete-button" onClick={onDeleteAccount} type="button">注销账号</Button>}</>}>

      {message && <div className="toast" role="status">{message}</div>}

      <section className="dashboard-page">
        {data.loading && <p role="status">正在加载当前页面...</p>}
        {data.error && <div className="admin-message error" role="alert">{data.error}<Button variant="secondary" className="secondary-btn" type="button" onClick={data.retry}>重试</Button></div>}
        {activeSection === 'overview' && <>
            <TodayMealsPanel data={data.today.data} loading={data.today.loading} error={data.today.error} onRefresh={data.today.invalidate}
              onPhoto={() => { fileInput.current?.scrollIntoView({ behavior: 'smooth', block: 'center' }); fileInput.current?.click() }}
              onManual={() => setJournalEditor({})} onReview={id => setRecordRequest({ id, action: 'correct' })}
              onRecords={() => setActiveSection('records')} onPlans={() => setActiveSection('plans')} />
            <section className="analysis-layout">
              <div className="card upload-card">
                <h2>
                  <Camera size={20} />
                  上传饮食图片
                </h2>
                <p>选择餐盘图片后，系统会识别食物、计算结构评分，并结合知识库生成建议。</p>
                <AiBudgetNotice revision={budgetRevision} />
                {storageWarning && <p className="hint" role="status">{storageWarning}</p>}
                <label className="file-picker">
                  <input ref={fileInput} type="file" aria-label="选择饮食图片" accept="image/*" onChange={onFileChange} disabled={loading || recovering} />
                  {preview ? <img src={preview} alt="待分析的饮食图片" /> : <span className="upload-placeholder"><span className="upload-placeholder-icon"><UploadCloud size={30} strokeWidth={1.5} /></span><strong>选择图片</strong><small>上传清晰的餐盘照片，开始营养分析</small></span>}
                </label>
                <div className="upload-actions">
                  <select aria-label="本次分析目标" value={goal} onChange={e => setGoal(e.target.value)} disabled={loading || recovering}>
                    {goals.map(g => (
                      <option key={g.value} value={g.value}>
                        {g.label}
                      </option>
                    ))}
                  </select>
                  <Button variant="primary" className="primary" onClick={analyze} disabled={loading || recovering}>
                    {recovering ? '查询中...' : loading ? '分析中...' : '开始分析'}
                  </Button>
                  {pendingKey && <Button variant="secondary" className="secondary-btn" type="button" disabled={recovering || loading}
                    onClick={() => recoverAnalysis(pendingKey)}>查询上次分析结果</Button>}
                  {pendingKey && <Button variant="secondary" className="secondary-btn" type="button" disabled={recovering || loading} onClick={async () => {
                    if (await confirm('上次请求可能仍在处理。确认另建一次分析吗？')) {
                      rememberAnalysis(null); submission.current = null; setAnalysisTask(null); setMessage('已准备新的分析，请点击开始分析。')
                    }
                  }}>另建一次分析</Button>}
                </div>
                <div className="goal-quick-link"><span>当前目标 · <strong>{goalLabel}</strong></span><Button variant="secondary" type="button" onClick={() => setActiveSection('goals')}>管理目标</Button></div>
              </div>

              <div className="card score-card">
                <h2>
                  <ShieldCheck size={20} />
                  本次分析结果
                </h2>
                <MealAnalysisProgress task={analysisTask} />
                {analysisTask?.status === 'INTERRUPTED' && <div className="analysis-failure-actions">
                  {analysisFailure(analysisTask.errorCode).action === 'replace' && <Button variant="secondary" type="button" onClick={() => fileInput.current?.click()}>选择另一张餐食照片</Button>}
                  {analysisFailure(analysisTask.errorCode).action === 'retry' && <><Button variant="secondary" type="button" onClick={analyze} disabled={!file || loading || recovering}>稍后用原图重试</Button>{!file && <p className="hint">请先重新选择原图，再点击重试。</p>}</>}
                </div>}
                {result && !loading && !recovering ? <><ResultView result={result} compact onReview={() => setCorrectionRecord({ id: result.recordId, foods: result.recognition.foods, uncertainFoods: result.recognition.uncertainFoods, revision: result.revision })} onViewDetails={() => setShowAnalysisDetail(true)} /><Button variant="secondary" className="secondary-btn correct-result-button" type="button" onClick={() => setCorrectionRecord({ id: result.recordId, foods: result.recognition.foods, uncertainFoods: result.recognition.uncertainFoods, revision: result.revision })}>纠正识别结果</Button></> : !analysisTask && <div className="analysis-empty"><span className="analysis-empty-icon"><Utensils size={34} strokeWidth={1.3} /></span><h3>这一餐，吃得怎么样？</h3><Empty text="上传图片后，这里会显示评分、食物识别、风险标签和饮食建议。" /><div className="analysis-steps"><span>01 选择图片</span><span>02 智能分析</span><span>03 获取建议</span></div></div>}
              </div>
            </section>
            <NextMealPanel day={data.day} revision={dataRevision} />
            </>}

        {activeSection === 'trends' && (trend ? <TrendCard trend={trend} /> : <div className="card"><h2>饮食趋势</h2><Empty text="暂无趋势数据，记录饮食后可在这里查看变化。" /></div>)}
        {activeSection === 'reports' && (showWeeklyDetail && report
          ? <WeeklyReportDetail key={[report.periodStart, report.periodEnd, dataRevision].join(':')} report={report} avgLabel={avgLabel} onClose={() => setShowWeeklyDetail(false)} />
          : report ? <ReportView report={report} avgLabel={avgLabel} onOpenDetail={() => setShowWeeklyDetail(true)} onGenerate={reportJob.generate} jobStatus={reportJob.status} jobProgress={reportJob.progress} />
          : <div className="card report-empty"><h2>本周饮食概览</h2>{!reportJob.status && <Empty text="暂无周报数据，记录饮食后可以生成专属周报。" />}<WeeklyReportProgress task={reportJob.progress} /><Button variant="secondary" className="secondary-btn" type="button" onClick={reportJob.generate} disabled={!!reportJob.status}>{reportJob.status === 'RETRY' ? '正在自动重试…' : reportJob.status ? '周报生成中…' : '生成今日周报 / 查看进度'}</Button></div>)}
        {activeSection === 'reports' && !showWeeklyDetail && <WeeklyReportHistoryPanel />}
        {activeSection === 'goals' && <><DietGoalsPanel {...{ goalLabel, goalDraft, setGoalDraft, saveGoal }} loading={savingGoal} /><DietPreferencesPanel onSaved={() => { reportJob.cancel(); setDataRevision(r => r + 1) }} /><BehaviorGoalsPanel /></>}
        {activeSection === 'plans' && <ActionPlansPanel />}
        {activeSection === 'records' && <><MealCoveragePanel revision={dataRevision} onChanged={() => setDataRevision(r => r + 1)} /><DietRecordsPanel onNew={() => setJournalEditor({})} onEdit={record => setRecordRequest({ id: record.id, action: 'edit' })} {...{ recordPageData, recordFiltersDraft, setRecordFiltersDraft, applyRecordFilters, resetRecordFilters, records, handleDeleteRecord, recordSize, setRecordPage, setRecordSize }} setCorrectionRecord={record => setRecordRequest({ id: record.id, action: 'correct' })} setViewingRecord={record => setRecordRequest({ id: record.id, action: 'view' })} loading={deletingRecord} recordsLoading={data.records.loading} /></>}

        <AssistantCard onError={setMessage} />
      </section>

      {showAnalysisDetail && result && <DetailDrawer title="完整餐食分析" onClose={() => setShowAnalysisDetail(false)}><ResultView result={result} onReview={() => { setShowAnalysisDetail(false); setCorrectionRecord({ id: result.recordId, foods: result.recognition.foods, uncertainFoods: result.recognition.uncertainFoods, revision: result.revision }) }} /></DetailDrawer>}
      {recordRequest && <MealRecordLoader key={`${recordRequest.id}:${recordRequest.action}`} id={recordRequest.id} onClose={() => setRecordRequest(null)} onLoaded={record => {
        if (recordRequest.action === 'edit') setJournalEditor({ record })
        else if (recordRequest.action === 'correct') setCorrectionRecord(record)
        else setViewingRecord(record)
        setRecordRequest(null)
      }} />}
      {viewingRecord && <DetailDrawer title="餐食记录详情" onClose={() => setViewingRecord(null)}>
        <p className="hint">用餐于 {formatTime(viewingRecord.occurredAt || viewingRecord.createdAt)} · {Object.entries(mealTypes).find(([key]) => key === viewingRecord.mealType)?.[1] || "未指定餐次"} · {goals.find(item => item.value === viewingRecord.goal)?.label || '饮食记录'}</p>
        <p className="hint">创建于 {formatTime(viewingRecord.createdAt)} · {viewingRecord.recordSource === "MANUAL" ? "手动记录" : "图片记录"}{!viewingRecord.timeConfirmed && " · 用餐时间待确认"}</p>
        <Button variant="secondary" className="secondary-btn" type="button" onClick={() => { setJournalEditor({ record: viewingRecord }); setViewingRecord(null) }}>修改用餐信息</Button>
        {viewingRecord.note && <p>{viewingRecord.note}</p>}
        {viewingRecord.imageUrl && <div className="record-detail-image"><AuthImage url={viewingRecord.imageUrl} alt="本次餐食图片" /></div>}
        <ResultView onReview={() => { setCorrectionRecord(viewingRecord); setViewingRecord(null) }} result={{ recordId: viewingRecord.id, revision: viewingRecord.revision, evaluation: { score: viewingRecord.score, summary: viewingRecord.summary, riskTags: viewingRecord.riskTags, scoreBreakdown: viewingRecord.scoreBreakdown }, recognition: { foods: viewingRecord.foods, uncertainFoods: viewingRecord.uncertainFoods }, advice: viewingRecord.advice, adviceStatus: viewingRecord.adviceStatus, adviceFailureCode: viewingRecord.adviceFailureCode }} />
        {!!Object.keys(viewingRecord.categoryCounts || {}).length && <><h3>食物分类统计</h3><div className="chips">{Object.entries(viewingRecord.categoryCounts).map(([key, count]) => <span key={key}>{categoryName(key)} · {count}</span>)}</div></>}
      </DetailDrawer>}
      {journalEditor && <MealJournalModal record={journalEditor.record} onClose={() => setJournalEditor(null)} onSaved={next => { reportJob.cancel(); setJournalEditor(null); setDataRevision(r => r + 1); setMessage("用餐记录已保存，趋势与周报会按实际用餐时间统计。"); if(result?.recordId === next.id) setResult(current => current ? { ...current, revision: next.revision } : null); }} />}
      {correctionRecord && <MealCorrectionModal record={correctionRecord} onClose={() => setCorrectionRecord(null)} onSaved={next => {
        reportJob.cancel()
        if (result?.recordId === next.recordId) setResult(next)
        setCorrectionRecord(null)
        setDataRevision(value => value + 1)
        setMessage(`识别已纠正，评分和画像已更新，旧周报已失效。${next.scoreChange ? `核对前 ${next.scoreChange.before} 分 → 核对后 ${next.scoreChange.after} 分。${next.scoreChange.previousVersion !== next.scoreChange.currentVersion ? '评分版本也已变化。' : ''}` : ''}`)
      }} />}
    </WorkspaceShell>
  )
}
