import AssistantEvaluationPanel from './admin/AssistantEvaluationPanel'
import AgentCoordinationPanel from './admin/AgentCoordinationPanel'
import TaskFailurePanel from './admin/TaskFailurePanel'
import AiBudgetMonitor from '../components/AiBudgetMonitor'
import { useWorkspaceSection } from '../hooks/useWorkspaceNavigation'
import { useDialog } from '../components/DialogProvider'
import AuditLogPanel from './admin/AuditLogPanel'
import NonFoodUploadsPanel from './admin/NonFoodUploadsPanel'
import KnowledgeManagementPanel from './admin/KnowledgeManagementPanel'
import MealManagementPanel from './admin/MealManagementPanel'
import UserManagementPanel from './admin/UserManagementPanel'
import { formatTime, categoryLabel, MetricSmall, Chart } from './admin/AdminUi'
import MealCorrectionEvaluationPanel from '../components/MealCorrectionEvaluationPanel'
import { useEffect, useState } from 'react'
import useAdminWorkspaceData from '../hooks/useAdminWorkspaceData'
import {
  adminAddKnowledgeChunk,
  adminDeleteKnowledgeChunk,
  adminDeleteMeal,
  adminDeleteUser,
  adminRagEvaluation,
  adminReindexKnowledge,
  adminUpdateKnowledgeChunk,
  getUser
} from '../api/client'
import WorkspaceShell from '../components/WorkspaceShell'
import DetailDrawer from '../components/DetailDrawer'
import SectionSwitcher from '../components/SectionSwitcher'
import { Activity, MessageCircle, BookOpen, ClipboardList, FlaskConical, LayoutDashboard, ListChecks, Network, RefreshCw, ScanLine, TriangleAlert, Users } from 'lucide-react'

const adminNavigation = [
  { key: 'overview', label: '系统概览', icon: LayoutDashboard, group: '工作台', description: '掌握平台运行情况，关注关键数据与待处理事项。' },
  { key: 'users', label: '用户管理', icon: Users, group: '业务管理', description: '查找用户、查看账户信息，管理平台用户。' },
  { key: 'meals', label: '饮食记录', icon: ClipboardList, group: '业务管理', description: '集中查看餐食记录与评分，了解用户的饮食情况。' },
  { key: 'nonfood', label: '异常上传', icon: TriangleAlert, group: '业务管理', description: '查看非饮食图片与上传预警，及时处理异常。' },
  { key: 'knowledge', label: 'RAG 知识库', icon: BookOpen, group: '智能服务', description: '维护饮食知识与检索内容，为智能建议提供可靠依据。' },
  { key: 'assistant-stats', label: '助手统计', icon: MessageCircle, group: '智能服务', description: '了解助手使用频率、常见问题与知识引用情况。' },
  { key: 'rag-evaluation', label: '检索质量评测', icon: BookOpen, group: '质量评测', description: '检查知识检索的命中率、排序质量与拒答表现。' },
  { key: 'assistant-evaluation', label: '助手评测', icon: FlaskConical, group: '质量评测', description: '评估回答质量，跟踪测试结果与样本改进。' },
  { key: 'meal-corrections', label: '识别纠错评测', icon: ScanLine, group: '质量评测', description: '审核识别纠错样本，持续改进餐食识别效果。' },
  { key: 'monitor', label: '系统监控', icon: Activity, group: '系统管理', description: '按服务、AI 调用、检索与上传情况查看完整运行指标。' },
  { key: 'agent-coordination', label: 'Agent 协调', icon: Network, group: '系统管理', description: '查看周报任务的执行过程、运行状态与协调记录。' },
  { key: 'task-failures', label: '失败任务', icon: TriangleAlert, group: '系统管理', description: '查看失败原因，安全重试任务并跟踪恢复结果。' },
  { key: 'audit', label: '审计日志', icon: ListChecks, group: '系统管理', description: '追踪管理操作，让每一次变更清晰可查。' }
]

const defaultUserFilters = { username: '', role: 'ALL' }
const defaultMealFilters = { username: '', from: '', to: '', minScore: '', maxScore: '' }
const defaultKnowledgeFilters = { keyword: '', source: 'all', sort: 'id' }
const defaultNonFoodFilters = { username: '', blockedOnly: false }
function formatBool(value) {
  return value ? '已配置' : '未配置'
}

function getReindexMessage(result) {
  const count = typeof result === 'number' ? result : result?.chunks ?? result?.count ?? result?.total ?? result?.indexedCount
  return count === undefined || count === null ? 'RAG 知识库已重建' : `RAG 知识库已重建，共写入 ${count} 条知识片段`
}

export default function AdminPage({ onBack, onLogout }) {
  const [ragBenchmark, setRagBenchmark] = useState(null)
  const [activeTab, setActiveTab] = useWorkspaceSection('admin')
  const { confirm } = useDialog()
  const [coordinationRefresh, setCoordinationRefresh] = useState(0)
  const [failureRefresh, setFailureRefresh] = useState(0)
  const [evaluationRefresh, setEvaluationRefresh] = useState(0)
  const [correctionRefresh, setCorrectionRefresh] = useState(0)
  const [monitorSection, setMonitorSection] = useState('services')
  const [showAlerts, setShowAlerts] = useState(false)
  const [showRagMetrics, setShowRagMetrics] = useState(false)
  const [assistantStatsSection, setAssistantStatsSection] = useState('trend')
  const [actionStates, setActionStates] = useState({})
  const actionLoading = actionStates[activeTab]?.loading || false
  const actionError = actionStates[activeTab]?.error || ''
  const message = actionStates[activeTab]?.message || ''
  function setLoading(value) { setActionStates(current => ({ ...current, [activeTab]: { ...current[activeTab], loading: value } })) }
  function setError(value) { setActionStates(current => ({ ...current, [activeTab]: { ...current[activeTab], error: value } })) }
  function setMessage(value) { setActionStates(current => ({ ...current, [activeTab]: { ...current[activeTab], message: value } })) }

  const [userPage, setUserPage] = useState(0)
  const [userSize, setUserSize] = useState(20)
  const [userFiltersDraft, setUserFiltersDraft] = useState(defaultUserFilters)
  const [userFilters, setUserFilters] = useState(defaultUserFilters)
  const [deletingUserId, setDeletingUserId] = useState(null)

  const [showMealManager, setShowMealManager] = useState(false)
  const [selectedMealIds, setSelectedMealIds] = useState([])
  const [deletingMealId, setDeletingMealId] = useState(null)
  const [mealPage, setMealPage] = useState(0)
  const [mealSize, setMealSize] = useState(20)
  const [mealFiltersDraft, setMealFiltersDraft] = useState(defaultMealFilters)
  const [mealFilters, setMealFilters] = useState(defaultMealFilters)

  const [knowledgeForm, setKnowledgeForm] = useState({ title: '', content: '' })
  const [knowledgeFiltersDraft, setKnowledgeFiltersDraft] = useState(defaultKnowledgeFilters)
  const [knowledgeFilters, setKnowledgeFilters] = useState(defaultKnowledgeFilters)
  const [knowledgePage, setKnowledgePage] = useState(0)
  const [knowledgeSize, setKnowledgeSize] = useState(8)
  const [editingChunkId, setEditingChunkId] = useState(null)
  const [editingChunkForm, setEditingChunkForm] = useState({ title: '', content: '' })
  const [deletingChunkId, setDeletingChunkId] = useState(null)
  const [viewingChunk, setViewingChunk] = useState(null)
  const [auditPage, setAuditPage] = useState(0)
  const [auditSize, setAuditSize] = useState(10)
  const [nonFoodPage, setNonFoodPage] = useState(0)
  const [nonFoodSize, setNonFoodSize] = useState(20)
  const [nonFoodFiltersDraft, setNonFoodFiltersDraft] = useState(defaultNonFoodFilters)
  const [nonFoodFilters, setNonFoodFilters] = useState(defaultNonFoodFilters)
  const data = useAdminWorkspaceData(activeTab, {
    userPage, userSize, userFilters, mealPage, mealSize, mealFilters,
    knowledgePage, knowledgeSize, knowledgeFilters, auditPage, auditSize,
    nonFoodPage, nonFoodSize, nonFoodFilters
  })
  const dashboard = data.dashboard.data, health = data.health.data
  const userPageData = data.users.data, mealPageData = data.meals.data
  const knowledgePageData = data.knowledge.data, auditPageData = data.audit.data
  const nonFoodPageData = data.nonfood.data, mealAnalytics = data.analytics.data
  const chunks = knowledgePageData.content || []
  const loading = actionLoading || data.loading
  const error = actionError || data.error
  useEffect(() => { setError(''); setMessage('') }, [activeTab])
  useEffect(() => {
    setSelectedMealIds(ids => {
      const next = ids.filter(id => (mealPageData.content || []).some(meal => meal.id === id))
      return next.length === ids.length ? ids : next
    })
  }, [mealPageData])

  const users = userPageData.content || []
  const meals = mealPageData.content || []
  const auditLogs = auditPageData.content || []
  const nonFoodUploads = nonFoodPageData.content || []
  const overview = dashboard?.overview || {}
  const system = dashboard?.system || {}
  const nonFoodUploadAlerts = dashboard?.nonFoodUploadAlerts || []
  const operations = dashboard?.operations || {}
  const ragEvaluation = dashboard?.ragEvaluation || {}
  const uploadTrends = dashboard?.uploadTrends || {}
  const assistantStats = dashboard?.assistantStats || {}
  const ragCategoryCounts = (ragEvaluation.categoryCounts || []).map(item => ({
    ...item,
    label: categoryLabel(item.label)
  }))
  const currentUser = getUser()

  function loadAdminData() {
    setError('')
    data.refresh()
    if (activeTab === 'agent-coordination') setCoordinationRefresh(value => value + 1)
    if (activeTab === 'task-failures') setFailureRefresh(value => value + 1)
    if (activeTab === 'assistant-evaluation') setEvaluationRefresh(value => value + 1)
    if (activeTab === 'meal-corrections') setCorrectionRefresh(value => value + 1)
  }

  function applyUserFilters(e) {
    e.preventDefault()
    setUserPage(0)
    setUserFilters({ ...userFiltersDraft })
  }

  function applyMealFilters(e) {
    e.preventDefault()
    setMealPage(0)
    setMealFilters({ ...mealFiltersDraft })
  }

  function applyKnowledgeFilters(e) {
    e.preventDefault()
    setKnowledgePage(0)
    setKnowledgeFilters({ ...knowledgeFiltersDraft })
  }

  function applyNonFoodFilters(e) {
    e.preventDefault()
    setNonFoodPage(0)
    setNonFoodFilters({ ...nonFoodFiltersDraft })
  }

  async function handleReindex() {
    setLoading(true)
    setMessage('')
    setError('')
    try {
      const result = await adminReindexKnowledge()
      data.refresh(['knowledge', 'dashboard', 'health', 'audit'])
      setMessage(getReindexMessage(result))
    } catch (err) {
      setError(err.message || '重建 RAG 知识库失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleRunRagEvaluation() {
    setLoading(true)
    setMessage('')
    setError('')
    try {
      const result = await adminRagEvaluation()
      setRagBenchmark(result)
      setMessage('RAG 评测已完成')
    } catch (err) {
      setError(err.message || 'RAG 评测失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteUser(user) {
    if (!await confirm(`确认删除用户 ${user.username}？该用户的饮食记录和图片也会删除。`, { danger: true, confirmText: '确认删除' })) return
    setDeletingUserId(user.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteUser(user.id)
      data.refresh(['users', 'meals', 'analytics', 'nonfood', 'dashboard', 'audit'])
      setMessage(`已删除用户 ${user.username}`)
    } catch (err) {
      setError(err.message || '删除用户失败')
    } finally {
      setDeletingUserId(null)
    }
  }

  async function handleDeleteMeal(meal) {
    if (!await confirm(`确认删除饮食记录 #${meal.id}？相关图片也会删除。`, { danger: true, confirmText: '确认删除' })) return
    setDeletingMealId(meal.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteMeal(meal.id)
      data.refresh(['meals', 'analytics', 'dashboard', 'audit'])
      setMessage(`已删除饮食记录 #${meal.id}`)
    } catch (err) {
      setError(err.message || '删除饮食记录失败')
    } finally {
      setDeletingMealId(null)
    }
  }

  async function handleDeleteSelectedMeals() {
    if (!selectedMealIds.length) return
    if (!await confirm(`确认删除选中的 ${selectedMealIds.length} 条饮食记录？`, { danger: true, confirmText: '确认删除' })) return
    setLoading(true)
    setMessage('')
    setError('')
    try {
      const count = selectedMealIds.length
      await Promise.all(selectedMealIds.map(id => adminDeleteMeal(id)))
      setSelectedMealIds([])
      data.refresh(['meals', 'analytics', 'dashboard', 'audit'])
      setMessage(`已删除 ${count} 条饮食记录`)
    } catch (err) {
      setError(err.message || '批量删除失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleAddKnowledgeChunk(e) {
    e.preventDefault()
    setLoading(true)
    setMessage('')
    setError('')
    try {
      await adminAddKnowledgeChunk(knowledgeForm)
      setKnowledgeForm({ title: '', content: '' })
      setKnowledgePage(0)
      data.refresh(['knowledge', 'dashboard', 'health', 'audit'])
      setMessage('已新增 RAG 知识片段')
    } catch (err) {
      setError(err.message || '新增知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  function startEditChunk(chunk) {
    setEditingChunkId(chunk.id)
    setEditingChunkForm({ title: chunk.title || '', content: chunk.content || chunk.contentPreview || '' })
  }

  async function handleUpdateKnowledgeChunk(e) {
    e.preventDefault()
    setLoading(true)
    setMessage('')
    setError('')
    try {
      await adminUpdateKnowledgeChunk(editingChunkId, editingChunkForm)
      setEditingChunkId(null)
      setEditingChunkForm({ title: '', content: '' })
      data.refresh(['knowledge', 'dashboard', 'audit'])
      setMessage('已更新 RAG 知识片段')
    } catch (err) {
      setError(err.message || '更新知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteKnowledgeChunk(chunk) {
    const name = chunk.title || `知识片段 ${chunk.id}`
    if (!await confirm(`确认删除 ${name}？`, { danger: true, confirmText: '确认删除' })) return
    setDeletingChunkId(chunk.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteKnowledgeChunk(chunk.id)
      data.refresh(['knowledge', 'dashboard', 'health', 'audit'])
      setMessage('已删除 RAG 知识片段')
    } catch (err) {
      setError(err.message || '删除知识片段失败')
    } finally {
      setDeletingChunkId(null)
    }
  }

  function toggleMealSelection(id) {
    setSelectedMealIds(ids => (ids.includes(id) ? ids.filter(item => item !== id) : [...ids, id]))
  }

  function toggleAllMealSelection() {
    setSelectedMealIds(selectedMealIds.length === meals.length ? [] : meals.map(meal => meal.id))
  }

  return (
    <WorkspaceShell mode="admin" user={getUser()} items={adminNavigation} activeKey={activeTab} onNavigate={setActiveTab}
      onLogout={onLogout} switchAction={{ label: '返回用户页', onClick: onBack }}
      actions={<button className="workspace-refresh" type="button" onClick={() => loadAdminData()} disabled={loading}><RefreshCw size={16} className={loading ? 'is-spinning' : ''} />刷新</button>}>
    <div className="admin-page">

      {error && <div className="admin-message error" role="alert">{error}</div>}
      {message && <div className="admin-message success" role="status">{message}</div>}
      {loading && <div className="admin-message" role="status">正在加载管理数据...</div>}

      {activeTab === 'overview' && <section className="admin-grid">
        <MetricCard value={overview.userCount ?? 0} label="注册用户" />
        <MetricCard value={overview.mealRecordCount ?? 0} label="饮食记录" />
        <MetricCard value={overview.todayNormalUploadCount ?? 0} label="今日正常上传" />
        <MetricCard value={overview.todayAbnormalUploadCount ?? 0} label="今日异常上传" />
        <MetricCard value={overview.knowledgeChunkCount ?? 0} label="RAG 片段" />
      </section>}

      {activeTab === 'meal-corrections' && <MealCorrectionEvaluationPanel key={correctionRefresh} />}
      {activeTab === 'overview' && <>
        <section className="admin-section overview-status">
          <div className="admin-section-head"><div><h2>运行摘要</h2><p>关键状态集中查看，完整指标可前往系统监控。</p></div><button className="secondary-btn" type="button" onClick={() => setActiveTab('monitor')}>查看系统监控</button></div>
          <div className="overview-status-grid"><Info label="服务健康" value={health?.status || '待获取'} /><Info label="近 7 天活跃用户" value={operations.activeUsers7d ?? 0} /><Info label="活跃违规预警" value={operations.activeNonFoodAlerts ?? 0} /></div>
          <div className="overview-alert-summary"><span>{nonFoodUploadAlerts.length ? '有 ' + nonFoodUploadAlerts.length + ' 位用户触发上传预警' : '暂无用户上传预警'}</span><button className="secondary-btn" type="button" onClick={() => setShowAlerts(true)}>查看预警详情</button></div>
        </section>
        <section className="admin-section"><h2>常用工作入口</h2><div className="overview-shortcuts">
          {[['users', '用户管理', '查找和管理平台账户'], ['meals', '饮食记录', '查看餐食与评分'], ['assistant-stats', '助手统计', '查看使用情况与问答趋势'], ['knowledge', 'RAG 知识库', '维护饮食知识内容']].map(([key,label,description]) => <button type="button" key={key} onClick={() => setActiveTab(key)}><strong>{label}</strong><span>{description}</span></button>)}
        </div></section>
      </>}
      {showAlerts && <DetailDrawer title="上传预警详情" onClose={() => setShowAlerts(false)}>{nonFoodUploadAlerts.length ? <>
          {nonFoodUploadAlerts.length > 0 && (
            <div className="admin-alert-panel">
              <div>
                <h3>非饮食图片上传预警</h3>
                <p>以下用户在短时间内连续上传了非饮食图片，请关注是否存在误用或异常操作。</p>
              </div>
              <div className="admin-alert-list">
                {nonFoodUploadAlerts.map(alert => (
                  <article key={alert.username}>
                    <strong>{alert.username}</strong>
                    <span>{alert.displayName || '未设置昵称'}</span>
                    <span>{alert.windowMinutes} 分钟内 {alert.count} 次，阈值 {alert.limit} 次</span>
                    <span>{formatTime(alert.lastTriggeredAt)}</span>
                  </article>
                ))}
              </div>
            </div>
          )}


      </> : <p className="hint">暂无用户上传预警。</p>}</DetailDrawer>}
      {activeTab === 'monitor' && <>
        <SectionSwitcher label="系统监控分类" value={monitorSection} onChange={setMonitorSection} items={[["services", "服务状态"], ["ai", "AI 调用"], ["retrieval", "检索指标"], ["uploads", "上传与活跃情况"]]} />
        <section className="admin-section">
          {monitorSection === 'services' && <>
          <h2>系统状态</h2>
          <div className="admin-info-grid">
            <Info label="后端" value={system.backend || 'Spring Boot'} />
            <Info label="数据库" value={system.database || 'PostgreSQL'} />
            <Info label="向量库" value={system.vectorDatabase || 'pgvector'} />
            <Info label="Embedding" value={system.embeddingMethod || 'HashEmbeddingService'} />
            <Info label="AI 模型" value={system.aiModel || '未配置'} />
            <Info label="API Key" value={formatBool(system.apiKeyConfigured)} />
            <Info label="Base URL" value={formatBool(system.baseUrlConfigured)} />
            <Info label="健康状态" value={health?.status || '未知'} />
          </div>

          <div className="admin-note">
            数据库：{health?.databaseReachable ? '正常' : '异常'}；Redis：{health?.redisReachable ? '正常' : '降级运行'}；上传目录：{health?.uploadDirectoryWritable ? '可写' : '不可写'}；知识片段：{health?.knowledgeChunkCount ?? overview.knowledgeChunkCount ?? 0} 条。
          </div>


          </>}
          {monitorSection === 'ai' && <><h2>AI 调用与耗时</h2>
          <AiBudgetMonitor />
          <div className="meal-analytics-grid">
            {(health?.aiCalls || system.aiCalls || []).length ? (
              (health?.aiCalls || system.aiCalls || []).map(call => (
                <div className="meal-chart-card" key={call.operation}>
                  <h3>{call.operation}</h3>
                  <p className={call.success ? 'status-ok' : 'status-bad'}>{call.success ? '最近调用成功' : '最近调用失败'}</p>
                  <p className="muted-text">{call.message}</p>
                  <p className="muted-text">{formatTime(call.at)}</p>
                </div>
              ))
            ) : (
              <div className="meal-chart-card wide">
                <h3>AI 调用状态</h3>
                <p className="muted-text">暂无 AI 调用记录。</p>
              </div>
            )}
          </div>

          {(system.aiMetrics || []).length > 0 && (
            <div className="admin-table-wrap ai-metrics-table">
              <table className="admin-table">
                <thead>
                  <tr><th>AI 操作</th><th>调用数</th><th>成功</th><th>失败</th><th>平均耗时</th><th>最大耗时</th></tr>
                </thead>
                <tbody>
                  {(system.aiMetrics || []).map(metric => (
                    <tr key={metric.operation}>
                      <td>{metric.operation}</td>
                      <td>{metric.calls}</td>
                      <td>{metric.successCount}</td>
                      <td>{metric.failureCount}</td>
                      <td>{metric.averageLatencyMs} ms</td>
                      <td>{metric.maxLatencyMs} ms</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}


          </>}
          {monitorSection === 'retrieval' && <><h2>知识检索指标</h2>
          <div className="rag-evaluation-grid">
            <MetricSmall label="24h 检索次数" value={ragEvaluation.searches24h ?? 0} />
            <MetricSmall label="平均 Top1 分数" value={ragEvaluation.averageTopScore24h ?? 0} />
            <MetricSmall label="平均检索分数" value={ragEvaluation.averageScore24h ?? 0} />
            <MetricSmall label="低置信检索" value={ragEvaluation.lowConfidenceSearches24h ?? 0} />
            <MetricSmall label="平均粗排 Top1" value={ragEvaluation.averageRoughTopScore24h ?? 0} />
            <MetricSmall label="平均精排原始 Top1" value={ragEvaluation.averageRerankerTopScore24h ?? 0} />
            <MetricSmall label="精排调用次数" value={ragEvaluation.rerankerAppliedSearches24h ?? 0} />
            <MetricSmall label="空证据次数" value={ragEvaluation.noAnswerSearches24h ?? 0} />
            <Chart title="RAG 命中类别分布" items={ragCategoryCounts} empty="暂无检索记录" wide />
          </div>


          </>}
          {monitorSection === 'uploads' && <><h2>上传与活跃情况</h2>
          <div className="operations-grid">
            <MetricSmall label="近 7 天活跃用户" value={operations.activeUsers7d ?? 0} />
            <MetricSmall label="近 7 天上传记录" value={operations.mealUploads7d ?? 0} />
            <MetricSmall label="AI 成功调用" value={operations.aiSuccessCount ?? 0} />
            <MetricSmall label="AI 失败调用" value={operations.aiFailureCount ?? 0} />
            <MetricSmall label="活跃违规预警" value={operations.activeNonFoodAlerts ?? 0} />
          </div>

          <div className="meal-analytics-grid">
            <Chart title="近 7 天正常上传" items={uploadTrends.dailyNormalUploads} empty="暂无正常上传记录" />
            <Chart title="近 7 天异常上传" items={uploadTrends.dailyAbnormalUploads} empty="暂无异常上传记录" />
            <Chart title="近 7 天触发预警" items={uploadTrends.dailyAlertTriggers} empty="暂无预警记录" />
            <Chart title="近 7 天受限用户" items={uploadTrends.dailyBlockedUsers} empty="暂无受限用户" />
          </div>


          </>}
        </section>
      </>}
      {activeTab === 'assistant-stats' && <section className="admin-section">
          <div className="assistant-stats-panel">
            <div className="admin-section-head compact-head">
              <div>
                <h2>助手统计</h2>
                <p>观察智能问答助手的使用频率、会话质量、常见问题和 RAG 引用情况。</p>
              </div>
            </div>

            <div className="operations-grid compact-five">
              <MetricSmall label="助手会话数量" value={assistantStats.conversationCount ?? 0} />
              <MetricSmall label="用户提问次数" value={assistantStats.questionCount ?? 0} />
              <MetricSmall label="平均轮次" value={assistantStats.averageTurns ?? 0} />
              <MetricSmall label="主动建议触发" value={assistantStats.proactiveTriggerCount ?? 0} />
              <MetricSmall label="RAG 引用知识数" value={(assistantStats.topRagReferences || []).length} />
            </div>

            <div className="assistant-stats-grid">
              <SectionSwitcher label="助手统计分类" value={assistantStatsSection} onChange={setAssistantStatsSection} items={[["trend", "问答趋势"], ["categories", "问题分类"], ["users", "用户使用情况"], ["references", "知识引用排行"]]} />
              {assistantStatsSection === 'trend' && <Chart title="最近 7 天助手问答趋势" items={assistantStats.questionTrend7d} empty="暂无助手问答记录" />}
              {assistantStatsSection === 'categories' && <Chart title="用户常问问题分类" items={assistantStats.questionCategories} empty="暂无问题分类数据" />}
              {assistantStatsSection === 'users' && <Chart title="每个用户提问次数" items={assistantStats.questionsByUser} empty="暂无用户提问数据" />}
              {assistantStatsSection === 'references' && <Chart title="RAG 知识引用排行" items={assistantStats.topRagReferences} empty="暂无 RAG 引用记录" />}
            </div>
          </div>

      </section>}

      {activeTab === 'users' && <UserManagementPanel {...{ applyUserFilters, currentUser, defaultUserFilters, deletingUserId, handleDeleteUser, loading, setUserFilters, setUserFiltersDraft, setUserPage, setUserSize, userFiltersDraft, userPageData, userSize, users }} />}

      {activeTab === 'meals' && <MealManagementPanel {...{ applyMealFilters, defaultMealFilters, deletingMealId, handleDeleteMeal, handleDeleteSelectedMeals, loading, mealAnalytics, mealFiltersDraft, mealPageData, mealSize, meals, selectedMealIds, setMealFilters, setMealFiltersDraft, setMealPage, setMealSize, setShowMealManager, showMealManager, toggleAllMealSelection, toggleMealSelection }} />}

      {activeTab === 'rag-evaluation' && <section className="admin-section"><h2>知识检索质量评测</h2><p className="hint">运行后可查看全部指标及逐题结果。</p>
          <div className="knowledge-toolbar">
            <button className="secondary-btn" type="button" onClick={handleRunRagEvaluation} disabled={loading}>运行 RAG 评测集</button>
          </div>

          {ragBenchmark && (
            <div className="rag-benchmark-panel">
              <div className="evaluation-summary-grid"><MetricSmall label="评测题数" value={ragBenchmark.caseCount ?? 0} /><MetricSmall label="Hit@3" value={ragBenchmark.hitAt3 ?? 0} /><MetricSmall label="MRR" value={ragBenchmark.mrr ?? 0} /><MetricSmall label="回归检查" value={ragBenchmark.regressionPassed ? '通过' : '未通过'} /></div>
              <div className="detail-entry"><span>完整指标包含初排、重排、拒答质量和检索耗时。</span><button className="secondary-btn" type="button" onClick={() => setShowRagMetrics(true)}>查看完整检索评测指标</button></div>
              {showRagMetrics && <DetailDrawer title="完整检索评测指标" onClose={() => setShowRagMetrics(false)}>
              <div className="operations-grid compact-five">
                <MetricSmall label="评测题数" value={ragBenchmark.caseCount ?? 0} />
                <MetricSmall label="有答案题数" value={ragBenchmark.answerableCaseCount ?? 0} />
                <MetricSmall label="无答案题数" value={ragBenchmark.noAnswerCaseCount ?? 0} />
                <MetricSmall label="初排 Hit@3" value={ragBenchmark.baselineHitAt3 ?? 0} />
                <MetricSmall label="Hit@3" value={ragBenchmark.hitAt3 ?? 0} />
                <MetricSmall label="初排 Hit@5" value={ragBenchmark.baselineHitAt5 ?? 0} />
                <MetricSmall label="Hit@5" value={ragBenchmark.hitAt5 ?? 0} />
                <MetricSmall label="初排 MRR" value={ragBenchmark.baselineMrr ?? 0} />
                <MetricSmall label="MRR" value={ragBenchmark.mrr ?? 0} />
                <MetricSmall label="初排 nDCG@5" value={ragBenchmark.baselineNdcgAt5 ?? 0} />
                <MetricSmall label="nDCG@5" value={ragBenchmark.ndcgAt5 ?? 0} />
                <MetricSmall label="初排类别@3" value={ragBenchmark.baselineCategoryAccuracy ?? 0} />
                <MetricSmall label="类别@3(1条)" value={ragBenchmark.categoryAccuracy ?? 0} />
                <MetricSmall label="重排模型" value={ragBenchmark.rerankerEnabled ? (ragBenchmark.rerankerProvider || '已启用') : '未启用'} />
                <MetricSmall label="回归检查" value={ragBenchmark.regressionPassed ? '通过' : '未通过'} />
                <MetricSmall label="平均检索耗时" value={`${ragBenchmark.averageRetrievalDurationMs ?? 0} ms`} />
                <MetricSmall label="平均重排耗时" value={`${ragBenchmark.averageRerankDurationMs ?? 0} ms`} />
                <MetricSmall label="平均候选数" value={ragBenchmark.averageCandidateCount ?? 0} />
                <MetricSmall label="过滤后平均结果" value={ragBenchmark.averageResultCount ?? 0} />
                <MetricSmall label="空结果率" value={ragBenchmark.emptyResultRate ?? 0} />
                <MetricSmall label="初排拒答准确率" value={ragBenchmark.baselineNoAnswerAccuracy ?? 0} />
                <MetricSmall label="最终拒答准确率" value={ragBenchmark.noAnswerAccuracy ?? 0} />
                <MetricSmall label="无答案误召回率" value={ragBenchmark.falsePositiveRate ?? 0} />
                <MetricSmall label="评测耗时" value={`${ragBenchmark.evaluationDurationMs ?? 0} ms`} />
              </div>
              </DetailDrawer>}

              <details className="workspace-disclosure"><summary>查看逐题检索结果</summary>
              <div className="admin-table-wrap">
                <table className="admin-table">
                  <thead>
                    <tr><th>问题</th><th>类型</th><th>Hit@3</th><th>Hit@5</th><th>首个相关排名</th><th>nDCG@5</th><th>期望类别</th><th>类别/拒答命中</th><th>粗排/精排/最终分</th><th>候选/输出</th><th>阈值/去重过滤</th><th>Top5 标题</th></tr>
                  </thead>
                  <tbody>
                    {(ragBenchmark.cases || []).map(item => (
                      <tr key={item.question}>
                        <td>{item.question}</td>
                        <td>{item.expectNoAnswer ? '无答案' : '有答案'}</td>
                        <td>{item.expectNoAnswer ? '-' : (item.hitAt3 ? '是' : '否')}</td>
                        <td>{item.expectNoAnswer ? '-' : (item.hitAt5 ? '是' : '否')}</td>
                        <td>{item.expectNoAnswer ? '-' : (item.firstRelevantRank || '-')}</td>
                        <td>{item.expectNoAnswer ? '-' : (item.ndcgAt5 ?? 0)}</td>
                        <td>{categoryLabel(item.expectedCategory)}</td>
                        <td>{item.expectNoAnswer ? (item.noAnswerCorrect ? '拒答正确' : '发生误召回') : (item.categoryHitAt3 ? '是' : '否')}</td>
                        <td>{item.topRoughScore ?? 0}/{item.topRerankerRawScore ?? '-'}/{item.topFinalScore ?? 0}</td>
                        <td>{item.candidateCount ?? 0}/{item.finalResultCount ?? 0}</td>
                        <td>{item.thresholdFilteredCount ?? 0}/{item.duplicateFilteredCount ?? 0}</td>
                        <td>{(item.topTitles || []).join(' / ') || '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              </details>
            </div>
          )}


      </section>}
      {activeTab === 'knowledge' && <KnowledgeManagementPanel {...{ applyKnowledgeFilters, chunks, defaultKnowledgeFilters, deletingChunkId, editingChunkForm, editingChunkId, handleAddKnowledgeChunk, handleDeleteKnowledgeChunk, handleReindex, handleUpdateKnowledgeChunk, knowledgeFiltersDraft, knowledgeForm, knowledgePageData, knowledgeSize, loading, setEditingChunkForm, setEditingChunkId, setKnowledgeFilters, setKnowledgeFiltersDraft, setKnowledgeForm, setKnowledgePage, setKnowledgeSize, setViewingChunk, startEditChunk }} />}

      <AssistantEvaluationPanel enabled={activeTab === 'assistant-evaluation'} refreshRevision={evaluationRefresh} />

      {activeTab === 'nonfood' && <NonFoodUploadsPanel {...{ applyNonFoodFilters, defaultNonFoodFilters, loading, nonFoodFiltersDraft, nonFoodPageData, nonFoodSize, nonFoodUploads, setNonFoodFilters, setNonFoodFiltersDraft, setNonFoodPage, setNonFoodSize }} />}

      <AgentCoordinationPanel enabled={activeTab === 'agent-coordination'} refreshRevision={coordinationRefresh} />
      {activeTab === 'task-failures' && <TaskFailurePanel refreshRevision={failureRefresh} onAction={() => data.refresh(['audit'])} />}

      {activeTab === 'audit' && <AuditLogPanel {...{ auditLogs, auditPageData, auditSize, loading, setAuditPage, setAuditSize }} />}

      {viewingChunk && <KnowledgeModal chunk={viewingChunk} onClose={() => setViewingChunk(null)} />}
    </div>
    </WorkspaceShell>
  )
}

function MetricCard({ value, label }) {
  const Icon = { 注册用户: Users, 饮食记录: ClipboardList, 今日正常上传: ScanLine, 今日异常上传: TriangleAlert, 'RAG 片段': BookOpen }[label] || LayoutDashboard
  return <div className="admin-card"><div className="admin-metric-heading"><span className="admin-label">{label}</span><Icon size={19} strokeWidth={1.6} /></div><div className="admin-number">{value}</div></div>
}

function Info({ label, value }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}

function KnowledgeModal({ chunk, onClose }) {
  return (
    <div className="modal-backdrop">
      <section className="modal-card knowledge-modal">
        <header className="modal-head">
          <div>
            <h2>{chunk.title || `知识片段 ${chunk.id}`}</h2>
            <p>{chunk.source === 'admin' ? '管理员添加' : '系统 Markdown'} · {categoryLabel(chunk.category)} · 版本 {chunk.sourceVersion || 'v1'} · {chunk.tokenCount ?? 0} Token · 更新：{formatTime(chunk.updatedAt)} · 命中 {chunk.hitCount || 0} 次 · 最近引用：{formatTime(chunk.lastHitAt)}</p>
          </div>
          <button className="secondary-btn" type="button" onClick={onClose}>关闭</button>
        </header>
        <div className="knowledge-full-content">{chunk.content || chunk.contentPreview || '暂无内容'}</div>
      </section>
    </div>
  )
}
