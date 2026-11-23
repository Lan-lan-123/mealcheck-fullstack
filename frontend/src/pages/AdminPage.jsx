import Button from '../components/Button'
import useAdminUsers from '../hooks/useAdminUsers'
import useAdminMeals from '../hooks/useAdminMeals'
import useAdminKnowledge from '../hooks/useAdminKnowledge'
import useAdminViewPreferences from '../hooks/useAdminViewPreferences'
import {
  adminNavigation,
  defaultUserFilters,
  defaultMealFilters,
  defaultKnowledgeFilters,
  defaultNonFoodFilters,
} from './admin/adminWorkspaceConfig'
import { useWorkspaceSection } from '../hooks/useWorkspaceNavigation'
import { useDialog } from '../components/DialogProvider'
import { categoryLabel } from './admin/AdminUi'
import { lazy, Suspense, useEffect, useState } from 'react'
import useAdminWorkspaceData from '../hooks/useAdminWorkspaceData'
import { getUser } from '../api/client'
import WorkspaceShell from '../components/WorkspaceShell'
import { RefreshCw } from 'lucide-react'

const KnowledgeModal = lazy(() => import('./admin/KnowledgeModal'))
const RagEvaluationPanel = lazy(() => import('./admin/RagEvaluationPanel'))
const AssistantStatisticsPanel = lazy(() => import('./admin/AssistantStatisticsPanel'))
const SystemMonitorPanel = lazy(() => import('./admin/SystemMonitorPanel'))
const AdminOverviewPanel = lazy(() => import('./admin/AdminOverviewPanel'))
const AssistantEvaluationPanel = lazy(() => import('./admin/AssistantEvaluationPanel'))
const AgentCoordinationPanel = lazy(() => import('./admin/AgentCoordinationPanel'))
const TaskFailurePanel = lazy(() => import('./admin/TaskFailurePanel'))
const AuditLogPanel = lazy(() => import('./admin/AuditLogPanel'))
const NonFoodUploadsPanel = lazy(() => import('./admin/NonFoodUploadsPanel'))
const KnowledgeManagementPanel = lazy(() => import('./admin/KnowledgeManagementPanel'))
const MealManagementPanel = lazy(() => import('./admin/MealManagementPanel'))
const UserManagementPanel = lazy(() => import('./admin/UserManagementPanel'))
const MealCorrectionEvaluationPanel = lazy(() => import('../components/MealCorrectionEvaluationPanel'))

export default function AdminPage({ onBack, onLogout }) {
  const { monitorSection, setMonitorSection, assistantStatsSection, setAssistantStatsSection } = useAdminViewPreferences()
  const [activeTab, setActiveTab] = useWorkspaceSection('admin')
  const [visitedTabs, setVisitedTabs] = useState(() => new Set([activeTab]))
  useEffect(() => {
    setVisitedTabs((current) => (current.has(activeTab) ? current : new Set([...current, activeTab])))
  }, [activeTab])
  function refresh(names) {
    data.refresh(names)
  }
  const { confirm } = useDialog()
  const [coordinationRefresh, setCoordinationRefresh] = useState(0)
  const [failureRefresh, setFailureRefresh] = useState(0)
  const [evaluationRefresh, setEvaluationRefresh] = useState(0)
  const [correctionRefresh, setCorrectionRefresh] = useState(0)

  const [actionStates, setActionStates] = useState({})
  const actionLoading = actionStates[activeTab]?.loading || false
  const actionError = actionStates[activeTab]?.error || ''
  const message = actionStates[activeTab]?.message || ''
  function setLoading(value) {
    setActionStates((current) => ({ ...current, [activeTab]: { ...current[activeTab], loading: value } }))
  }
  function setError(value) {
    setActionStates((current) => ({ ...current, [activeTab]: { ...current[activeTab], error: value } }))
  }
  function setMessage(value) {
    setActionStates((current) => ({ ...current, [activeTab]: { ...current[activeTab], message: value } }))
  }

  const {
    userPage,
    setUserPage,
    userSize,
    setUserSize,
    userFiltersDraft,
    setUserFiltersDraft,
    userFilters,
    setUserFilters,
    deletingUserId,
    setDeletingUserId,
    applyUserFilters,
    handleDeleteUser,
    updatingUserId,
    handleSetUserEnabled,
  } = useAdminUsers({ confirm, setError, setMessage, setLoading, refresh })

  const {
    showMealManager,
    setShowMealManager,
    selectedMealIds,
    setSelectedMealIds,
    deletingMealId,
    setDeletingMealId,
    mealPage,
    setMealPage,
    mealSize,
    setMealSize,
    mealFiltersDraft,
    setMealFiltersDraft,
    mealFilters,
    setMealFilters,
    applyMealFilters,
    handleDeleteMeal,
    handleDeleteSelectedMeals,
    toggleMealSelection,
    toggleAllMealSelection,
  } = useAdminMeals({
    confirm,
    setError,
    setMessage,
    setLoading,
    refresh,
    getMeals: () => data.meals.data.content || [],
  })

  const {
    knowledgeForm,
    setKnowledgeForm,
    knowledgeFiltersDraft,
    setKnowledgeFiltersDraft,
    knowledgeFilters,
    setKnowledgeFilters,
    knowledgePage,
    setKnowledgePage,
    knowledgeSize,
    setKnowledgeSize,
    editingChunkId,
    setEditingChunkId,
    editingChunkForm,
    setEditingChunkForm,
    deletingChunkId,
    setDeletingChunkId,
    viewingChunk,
    setViewingChunk,
    applyKnowledgeFilters,
    handleReindex,
    handleAddKnowledgeChunk,
    startEditChunk,
    handleUpdateKnowledgeChunk,
    handleDeleteKnowledgeChunk,
  } = useAdminKnowledge({ confirm, setError, setMessage, setLoading, refresh })

  const [auditPage, setAuditPage] = useState(0)
  const [auditSize, setAuditSize] = useState(10)
  const [nonFoodPage, setNonFoodPage] = useState(0)
  const [nonFoodSize, setNonFoodSize] = useState(20)
  const [nonFoodFiltersDraft, setNonFoodFiltersDraft] = useState(defaultNonFoodFilters)
  const [nonFoodFilters, setNonFoodFilters] = useState(defaultNonFoodFilters)
  const data = useAdminWorkspaceData(activeTab, {
    userPage,
    userSize,
    userFilters,
    mealPage,
    mealSize,
    mealFilters,
    knowledgePage,
    knowledgeSize,
    knowledgeFilters,
    auditPage,
    auditSize,
    nonFoodPage,
    nonFoodSize,
    nonFoodFilters,
  })
  const dashboard = data.dashboard.data,
    health = data.health.data
  const userPageData = data.users.data,
    mealPageData = data.meals.data
  const knowledgePageData = data.knowledge.data,
    auditPageData = data.audit.data
  const nonFoodPageData = data.nonfood.data,
    mealAnalytics = data.analytics.data
  const chunks = knowledgePageData.content || []
  const loading = actionLoading || data.loading
  const error = actionError || data.error
  useEffect(() => {
    setError('')
    setMessage('')
  }, [activeTab])
  useEffect(() => {
    setSelectedMealIds((ids) => {
      const next = ids.filter((id) => (mealPageData.content || []).some((meal) => meal.id === id))
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
  const ragCategoryCounts = (ragEvaluation.categoryCounts || []).map((item) => ({
    ...item,
    label: categoryLabel(item.label),
  }))
  const currentUser = getUser()

  function loadAdminData() {
    setError('')
    data.refresh()
    if (activeTab === 'agent-coordination') setCoordinationRefresh((value) => value + 1)
    if (activeTab === 'task-failures') setFailureRefresh((value) => value + 1)
    if (activeTab === 'assistant-evaluation') setEvaluationRefresh((value) => value + 1)
    if (activeTab === 'meal-corrections') setCorrectionRefresh((value) => value + 1)
  }

  function applyNonFoodFilters(e) {
    e.preventDefault()
    setNonFoodPage(0)
    setNonFoodFilters({ ...nonFoodFiltersDraft })
  }

  return (
    <WorkspaceShell
      mode="admin"
      user={getUser()}
      items={adminNavigation}
      activeKey={activeTab}
      onNavigate={setActiveTab}
      onLogout={onLogout}
      switchAction={{ label: '返回用户页', onClick: onBack }}
      actions={
        <Button
          variant="plain"
          className="workspace-refresh"
          type="button"
          onClick={() => loadAdminData()}
          disabled={loading}
        >
          <RefreshCw size={16} className={loading ? 'is-spinning' : ''} />
          刷新
        </Button>
      }
    >
      <div className="admin-page">
        <Suspense fallback={<p role="status">正在加载管理页面…</p>}>
          {error && (
            <div className="admin-message error" role="alert">
              {error}
            </div>
          )}
          {message && (
            <div className="admin-message success" role="status">
              {message}
            </div>
          )}
          {loading && (
            <div className="admin-message" role="status">
              正在加载管理数据...
            </div>
          )}

          {activeTab === 'overview' && (
            <AdminOverviewPanel {...{ overview, health, operations, nonFoodUploadAlerts, setActiveTab }} />
          )}

          {activeTab === 'meal-corrections' && <MealCorrectionEvaluationPanel key={correctionRefresh} />}

          {activeTab === 'monitor' && (
            <SystemMonitorPanel
              {...{ system, health, overview, operations, ragEvaluation, uploadTrends, ragCategoryCounts, monitorSection, setMonitorSection }}
              onSettingsSaved={() => refresh(['dashboard', 'health'])}
            />
          )}
          {activeTab === 'assistant-stats' && <AssistantStatisticsPanel {...{ assistantStats, assistantStatsSection, setAssistantStatsSection }} />}

          {activeTab === 'users' && (
            <UserManagementPanel
              {...{
                applyUserFilters,
                currentUser,
                defaultUserFilters,
                deletingUserId,
                handleDeleteUser,
                updatingUserId,
                handleSetUserEnabled,
                loading,
                setUserFilters,
                setUserFiltersDraft,
                setUserPage,
                setUserSize,
                userFiltersDraft,
                userPageData,
                userSize,
                users,
              }}
            />
          )}

          {activeTab === 'meals' && (
            <MealManagementPanel
              {...{
                applyMealFilters,
                defaultMealFilters,
                deletingMealId,
                handleDeleteMeal,
                handleDeleteSelectedMeals,
                loading,
                mealAnalytics,
                mealFiltersDraft,
                mealPageData,
                mealSize,
                meals,
                selectedMealIds,
                setMealFilters,
                setMealFiltersDraft,
                setMealPage,
                setMealSize,
                setShowMealManager,
                showMealManager,
                toggleAllMealSelection,
                toggleMealSelection,
              }}
            />
          )}

          {visitedTabs.has('rag-evaluation') && <RagEvaluationPanel enabled={activeTab === 'rag-evaluation'} />}
          {activeTab === 'knowledge' && (
            <KnowledgeManagementPanel
              {...{
                applyKnowledgeFilters,
                chunks,
                defaultKnowledgeFilters,
                deletingChunkId,
                editingChunkForm,
                editingChunkId,
                handleAddKnowledgeChunk,
                handleDeleteKnowledgeChunk,
                handleReindex,
                handleUpdateKnowledgeChunk,
                knowledgeFiltersDraft,
                knowledgeForm,
                knowledgePageData,
                knowledgeSize,
                loading,
                setEditingChunkForm,
                setEditingChunkId,
                setKnowledgeFilters,
                setKnowledgeFiltersDraft,
                setKnowledgeForm,
                setKnowledgePage,
                setKnowledgeSize,
                setViewingChunk,
                startEditChunk,
              }}
            />
          )}

          {visitedTabs.has('assistant-evaluation') && (
            <AssistantEvaluationPanel
              enabled={activeTab === 'assistant-evaluation'}
              refreshRevision={evaluationRefresh}
            />
          )}

          {activeTab === 'nonfood' && (
            <NonFoodUploadsPanel
              {...{
                applyNonFoodFilters,
                defaultNonFoodFilters,
                loading,
                nonFoodFiltersDraft,
                nonFoodPageData,
                nonFoodSize,
                nonFoodUploads,
                setNonFoodFilters,
                setNonFoodFiltersDraft,
                setNonFoodPage,
                setNonFoodSize,
              }}
            />
          )}

          {visitedTabs.has('agent-coordination') && (
            <AgentCoordinationPanel
              enabled={activeTab === 'agent-coordination'}
              refreshRevision={coordinationRefresh}
            />
          )}
          {activeTab === 'task-failures' && (
            <TaskFailurePanel refreshRevision={failureRefresh} onAction={() => data.refresh(['audit'])} />
          )}

          {activeTab === 'audit' && (
            <AuditLogPanel {...{ auditLogs, auditPageData, auditSize, loading, setAuditPage, setAuditSize }} />
          )}

          {viewingChunk && <KnowledgeModal chunk={viewingChunk} onClose={() => setViewingChunk(null)} />}
        </Suspense>
      </div>
    </WorkspaceShell>
  )
}
