import { useEffect, useState } from 'react'
import {
  adminAddKnowledgeChunk,
  adminAuditLogs,
  adminDashboard,
  adminDeleteKnowledgeChunk,
  adminDeleteMeal,
  adminDeleteUser,
  adminKnowledgeChunks,
  adminMealAnalytics,
  adminMeals,
  adminReindexKnowledge,
  adminUpdateKnowledgeChunk,
  adminUsers,
  getUser,
  healthCheck
} from '../api/client'
import MiniBars from '../components/MiniBars'

const defaultUserFilters = { username: '', role: 'ALL' }
const defaultMealFilters = { username: '', from: '', to: '', minScore: '', maxScore: '' }
const defaultKnowledgeFilters = { keyword: '', source: 'all', sort: 'id' }

function emptyPage(size = 20) {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 }
}

function normalizePage(data, size = 20) {
  if (Array.isArray(data)) return { content: data, page: 0, size, totalElements: data.length, totalPages: data.length ? 1 : 0 }
  return data || emptyPage(size)
}

function formatTime(value) {
  if (!value) return '暂无'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return value
  }
}

function formatBool(value) {
  return value ? '已配置' : '未配置'
}

function getReindexMessage(result) {
  const count = typeof result === 'number' ? result : result?.chunks ?? result?.count ?? result?.total ?? result?.indexedCount
  return count === undefined || count === null ? 'RAG 知识库已重建' : `RAG 知识库已重建，共写入 ${count} 条知识片段`
}

function categoryLabel(category) {
  const labels = {
    fat_loss: '减脂',
    muscle_gain: '增肌',
    high_oil: '高油',
    sugar: '糖饮甜品',
    vegetable: '蔬菜',
    staple: '主食',
    general: '通用'
  }
  return labels[category] || category || '通用'
}

export default function AdminPage({ onBack, onLogout }) {
  const [dashboard, setDashboard] = useState(null)
  const [health, setHealth] = useState(null)
  const [userPageData, setUserPageData] = useState(emptyPage())
  const [mealPageData, setMealPageData] = useState(emptyPage())
  const [knowledgePageData, setKnowledgePageData] = useState(emptyPage(8))
  const [auditPageData, setAuditPageData] = useState(emptyPage(10))
  const [mealAnalytics, setMealAnalytics] = useState(null)
  const [chunks, setChunks] = useState([])

  const [activeTab, setActiveTab] = useState('overview')
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

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

  const users = userPageData.content || []
  const meals = mealPageData.content || []
  const auditLogs = auditPageData.content || []
  const overview = dashboard?.overview || {}
  const system = dashboard?.system || {}
  const currentUser = getUser()

  async function loadAdminData() {
    setLoading(true)
    setError('')
    try {
      const [dashboardData, healthData, userData, mealData, analyticsData, chunkData, auditData] = await Promise.all([
        adminDashboard(),
        healthCheck(),
        adminUsers({ page: userPage, size: userSize, ...userFilters }),
        adminMeals({ page: mealPage, size: mealSize, ...mealFilters }),
        adminMealAnalytics(mealFilters),
        adminKnowledgeChunks({ page: knowledgePage, size: knowledgeSize, ...knowledgeFilters }),
        adminAuditLogs({ page: auditPage, size: auditSize })
      ])

      const nextMealPage = normalizePage(mealData, mealSize)
      const nextKnowledgePage = normalizePage(chunkData, knowledgeSize)
      setDashboard(dashboardData)
      setHealth(healthData)
      setUserPageData(normalizePage(userData, userSize))
      setMealPageData(nextMealPage)
      setMealAnalytics(analyticsData)
      setKnowledgePageData(nextKnowledgePage)
      setChunks(nextKnowledgePage.content || [])
      setAuditPageData(normalizePage(auditData, auditSize))
      setSelectedMealIds(ids => ids.filter(id => (nextMealPage.content || []).some(meal => meal.id === id)))
    } catch (err) {
      setError(err.message || '加载管理数据失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAdminData()
  }, [userPage, userSize, userFilters, mealPage, mealSize, mealFilters, knowledgePage, knowledgeSize, knowledgeFilters, auditPage, auditSize])

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

  async function handleReindex() {
    setLoading(true)
    setMessage('')
    setError('')
    try {
      const result = await adminReindexKnowledge()
      await loadAdminData()
      setMessage(getReindexMessage(result))
    } catch (err) {
      setError(err.message || '重建 RAG 知识库失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteUser(user) {
    if (!window.confirm(`确认删除用户 ${user.username}？该用户的饮食记录和图片也会删除。`)) return
    setDeletingUserId(user.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteUser(user.id)
      await loadAdminData()
      setMessage(`已删除用户 ${user.username}`)
    } catch (err) {
      setError(err.message || '删除用户失败')
    } finally {
      setDeletingUserId(null)
    }
  }

  async function handleDeleteMeal(meal) {
    if (!window.confirm(`确认删除饮食记录 #${meal.id}？相关图片也会删除。`)) return
    setDeletingMealId(meal.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteMeal(meal.id)
      await loadAdminData()
      setMessage(`已删除饮食记录 #${meal.id}`)
    } catch (err) {
      setError(err.message || '删除饮食记录失败')
    } finally {
      setDeletingMealId(null)
    }
  }

  async function handleDeleteSelectedMeals() {
    if (!selectedMealIds.length) return
    if (!window.confirm(`确认删除选中的 ${selectedMealIds.length} 条饮食记录？`)) return
    setLoading(true)
    setMessage('')
    setError('')
    try {
      const count = selectedMealIds.length
      await Promise.all(selectedMealIds.map(id => adminDeleteMeal(id)))
      setSelectedMealIds([])
      await loadAdminData()
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
      await loadAdminData()
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
      await loadAdminData()
      setMessage('已更新 RAG 知识片段')
    } catch (err) {
      setError(err.message || '更新知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteKnowledgeChunk(chunk) {
    const name = chunk.title || `知识片段 ${chunk.id}`
    if (!window.confirm(`确认删除 ${name}？`)) return
    setDeletingChunkId(chunk.id)
    setMessage('')
    setError('')
    try {
      await adminDeleteKnowledgeChunk(chunk.id)
      await loadAdminData()
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
    <div className="admin-page">
      <section className="admin-header">
        <div>
          <h1>管理后台</h1>
          <p>管理用户、饮食记录、RAG 知识库、系统状态和管理员审计日志。</p>
        </div>
        <div className="admin-header-actions">
          <button className="secondary-btn" type="button" onClick={loadAdminData} disabled={loading}>刷新</button>
          <button className="secondary-btn" type="button" onClick={onBack}>返回用户页</button>
          {onLogout && <button className="secondary-btn" type="button" onClick={onLogout}>退出登录</button>}
        </div>
      </section>

      {error && <div className="admin-message error">{error}</div>}
      {message && <div className="admin-message success">{message}</div>}
      {loading && <div className="admin-message">正在加载管理数据...</div>}

      <section className="admin-grid">
        <MetricCard value={overview.userCount ?? 0} label="注册用户" />
        <MetricCard value={overview.mealRecordCount ?? 0} label="饮食记录" />
        <MetricCard value={overview.todayMealRecordCount ?? 0} label="今日上传" />
        <MetricCard value={overview.knowledgeChunkCount ?? 0} label="RAG 片段" />
      </section>

      <section className="admin-tabs">
        {[
          ['overview', '系统概览'],
          ['users', '用户管理'],
          ['meals', '饮食记录'],
          ['knowledge', 'RAG 知识库'],
          ['audit', '审计日志']
        ].map(([key, label]) => (
          <button key={key} type="button" className={activeTab === key ? 'active' : ''} onClick={() => setActiveTab(key)}>
            {label}
          </button>
        ))}
      </section>

      {activeTab === 'overview' && (
        <section className="admin-section">
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
        </section>
      )}

      {activeTab === 'users' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>用户管理</h2>
              <p>查看注册用户、角色、注册时间和最后一次上传时间。</p>
            </div>
          </div>
          <form className="filter-grid compact" onSubmit={applyUserFilters}>
            <input value={userFiltersDraft.username} onChange={e => setUserFiltersDraft({ ...userFiltersDraft, username: e.target.value })} placeholder="搜索用户名或昵称" />
            <select value={userFiltersDraft.role} onChange={e => setUserFiltersDraft({ ...userFiltersDraft, role: e.target.value })}>
              <option value="ALL">全部角色</option>
              <option value="ADMIN">管理员</option>
              <option value="USER">普通用户</option>
            </select>
            <button className="secondary-btn" type="submit">筛选</button>
            <button className="secondary-btn" type="button" onClick={() => { setUserFiltersDraft(defaultUserFilters); setUserFilters(defaultUserFilters); setUserPage(0) }}>重置</button>
          </form>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>ID</th><th>用户名</th><th>昵称</th><th>角色</th><th>最后上传</th><th>注册时间</th><th>操作</th></tr>
              </thead>
              <tbody>
                {users.length ? users.map(user => (
                  <tr key={user.id}>
                    <td>{user.id}</td>
                    <td>{user.username}</td>
                    <td>{user.displayName || '暂无'}</td>
                    <td><span className={user.role === 'ADMIN' ? 'role-tag admin' : 'role-tag'}>{user.role || 'USER'}</span></td>
                    <td>{formatTime(user.lastUploadAt)}</td>
                    <td>{formatTime(user.createdAt)}</td>
                    <td><button className="table-danger-btn" type="button" onClick={() => handleDeleteUser(user)} disabled={loading || deletingUserId === user.id || user.username === currentUser?.username}>{deletingUserId === user.id ? '删除中...' : '删除'}</button></td>
                  </tr>
                )) : <tr><td colSpan="7" className="empty-cell">暂无用户</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={userPageData} pageSize={userSize} setPage={setUserPage} setPageSize={setUserSize} loading={loading} />
        </section>
      )}

      {activeTab === 'meals' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>全站饮食记录</h2>
              <p>按用户、日期和评分筛选记录，并查看整体可视化分析。</p>
            </div>
            <button className={showMealManager ? 'primary-btn' : 'secondary-btn'} type="button" onClick={() => setShowMealManager(value => !value)}>{showMealManager ? '收起管理' : '管理全体饮食记录'}</button>
          </div>

          {showMealManager && (
            <div className="meal-manager">
              <form className="filter-grid" onSubmit={applyMealFilters}>
                <input value={mealFiltersDraft.username} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, username: e.target.value })} placeholder="用户名" />
                <input type="date" value={mealFiltersDraft.from} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, from: e.target.value })} />
                <input type="date" value={mealFiltersDraft.to} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, to: e.target.value })} />
                <input type="number" min="0" max="100" value={mealFiltersDraft.minScore} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, minScore: e.target.value })} placeholder="最低评分" />
                <input type="number" min="0" max="100" value={mealFiltersDraft.maxScore} onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, maxScore: e.target.value })} placeholder="最高评分" />
                <button className="secondary-btn" type="submit">筛选</button>
                <button className="secondary-btn" type="button" onClick={() => { setMealFiltersDraft(defaultMealFilters); setMealFilters(defaultMealFilters); setMealPage(0) }}>重置</button>
              </form>
              <div className="meal-manager-actions">
                <button className="secondary-btn" type="button" onClick={toggleAllMealSelection}>{selectedMealIds.length === meals.length && meals.length > 0 ? '取消全选' : '全选本页'}</button>
                <button className="table-danger-btn" type="button" onClick={handleDeleteSelectedMeals} disabled={!selectedMealIds.length || loading}>批量删除{selectedMealIds.length ? ` (${selectedMealIds.length})` : ''}</button>
              </div>
            </div>
          )}

          <div className="meal-analytics-grid">
            <MetricSmall label="筛选记录数" value={mealAnalytics?.total ?? 0} />
            <MetricSmall label="平均评分" value={mealAnalytics?.averageScore ?? 0} />
            <Chart title="评分分布" items={mealAnalytics?.scoreBands} empty="暂无评分数据" />
            <Chart title="用户提交量" items={mealAnalytics?.userCounts} empty="暂无用户数据" />
            <Chart title="常见食物" items={mealAnalytics?.foodCounts} empty="暂无食物数据" wide />
            <Chart title="低分原因/风险" items={mealAnalytics?.riskTagCounts} empty="暂无风险数据" wide />
            <Chart title="平均分趋势" items={mealAnalytics?.dailyAverageScores} empty="暂无趋势数据" wide />
          </div>

          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>{showMealManager && <th>选择</th>}<th>ID</th><th>用户</th><th>评分</th><th>识别食物</th><th>摘要</th><th>提交时间</th>{showMealManager && <th>操作</th>}</tr>
              </thead>
              <tbody>
                {meals.length ? meals.map(meal => (
                  <tr key={meal.id}>
                    {showMealManager && <td><input type="checkbox" checked={selectedMealIds.includes(meal.id)} onChange={() => toggleMealSelection(meal.id)} /></td>}
                    <td>{meal.id}</td>
                    <td>{meal.username || '未知用户'}</td>
                    <td><strong>{meal.score ?? '暂无'}</strong></td>
                    <td><FoodTags names={meal.foodNames} /></td>
                    <td className="summary-cell">{meal.summary || '暂无'}</td>
                    <td>{formatTime(meal.createdAt)}</td>
                    {showMealManager && <td><button className="table-danger-btn" type="button" onClick={() => handleDeleteMeal(meal)} disabled={deletingMealId === meal.id || loading}>{deletingMealId === meal.id ? '删除中...' : '删除'}</button></td>}
                  </tr>
                )) : <tr><td colSpan={showMealManager ? 8 : 6} className="empty-cell">暂无饮食记录</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={mealPageData} pageSize={mealSize} setPage={setMealPage} setPageSize={setMealSize} loading={loading} />
        </section>
      )}

      {activeTab === 'knowledge' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>RAG 知识库维护</h2>
              <p>新增、编辑、删除知识片段，并查看来源、分类、命中次数和最近引用时间。</p>
            </div>
            <button className="primary-btn" type="button" onClick={handleReindex} disabled={loading}>重建系统 RAG 知识库</button>
          </div>

          <div className="admin-note">系统会根据知识标题和内容自动标记分类，例如减脂、增肌、高油、糖饮甜品、蔬菜和主食。检索时同类知识会优先排序。</div>

          <form className="filter-grid compact" onSubmit={applyKnowledgeFilters}>
            <input value={knowledgeFiltersDraft.keyword} onChange={e => setKnowledgeFiltersDraft({ ...knowledgeFiltersDraft, keyword: e.target.value })} placeholder="按标题或内容搜索" />
            <select value={knowledgeFiltersDraft.source} onChange={e => setKnowledgeFiltersDraft({ ...knowledgeFiltersDraft, source: e.target.value })}>
              <option value="all">全部来源</option>
              <option value="admin">管理员手动添加</option>
              <option value="diet_guides.md">系统 Markdown</option>
            </select>
            <select value={knowledgeFiltersDraft.sort} onChange={e => setKnowledgeFiltersDraft({ ...knowledgeFiltersDraft, sort: e.target.value })}>
              <option value="id">按 ID</option>
              <option value="hits">按命中次数</option>
              <option value="recentHit">按最近引用</option>
            </select>
            <button className="secondary-btn" type="submit">搜索</button>
            <button className="secondary-btn" type="button" onClick={() => { setKnowledgeFiltersDraft(defaultKnowledgeFilters); setKnowledgeFilters(defaultKnowledgeFilters); setKnowledgePage(0) }}>重置</button>
          </form>

          <form className="knowledge-form" onSubmit={handleAddKnowledgeChunk}>
            <input value={knowledgeForm.title} onChange={e => setKnowledgeForm({ ...knowledgeForm, title: e.target.value })} placeholder="知识标题" />
            <textarea value={knowledgeForm.content} onChange={e => setKnowledgeForm({ ...knowledgeForm, content: e.target.value })} placeholder="知识内容" rows="4" />
            <button className="secondary-btn" type="submit" disabled={loading}>新增 RAG 知识片段</button>
          </form>

          <div className="knowledge-list">
            {chunks.length ? chunks.map(chunk => (
              <article className="knowledge-card" key={chunk.id}>
                {editingChunkId === chunk.id ? (
                  <form className="knowledge-edit-form" onSubmit={handleUpdateKnowledgeChunk}>
                    <input value={editingChunkForm.title} onChange={e => setEditingChunkForm({ ...editingChunkForm, title: e.target.value })} />
                    <textarea value={editingChunkForm.content} onChange={e => setEditingChunkForm({ ...editingChunkForm, content: e.target.value })} rows="5" />
                    <div className="knowledge-card-actions">
                      <button className="secondary-btn" type="submit" disabled={loading}>保存</button>
                      <button className="secondary-btn" type="button" onClick={() => setEditingChunkId(null)}>取消</button>
                    </div>
                  </form>
                ) : (
                  <>
                    <div className="knowledge-card-head">
                      <h3>{chunk.title || `知识片段 ${chunk.id}`}</h3>
                      <span>{chunk.contentLength ?? 0} 字</span>
                    </div>
                    <div className="knowledge-meta">
                      <span className={chunk.source === 'admin' ? 'source-tag admin' : 'source-tag'}>{chunk.source === 'admin' ? '管理员添加' : '系统 Markdown'}</span>
                      <span>{categoryLabel(chunk.category)}</span>
                      <span>命中 {chunk.hitCount || 0} 次</span>
                      <span>最近引用：{formatTime(chunk.lastHitAt)}</span>
                    </div>
                    <p>{chunk.contentPreview || '暂无内容预览'}</p>
                    <div className="knowledge-card-actions">
                      <button className="secondary-btn" type="button" onClick={() => setViewingChunk(chunk)}>查看详情</button>
                      <button className="secondary-btn" type="button" onClick={() => startEditChunk(chunk)}>编辑</button>
                      <button className="table-danger-btn" type="button" onClick={() => handleDeleteKnowledgeChunk(chunk)} disabled={deletingChunkId === chunk.id}>{deletingChunkId === chunk.id ? '删除中...' : '删除'}</button>
                    </div>
                  </>
                )}
              </article>
            )) : <div className="empty-box">暂无 RAG 知识片段</div>}
          </div>
          <Pagination pageData={knowledgePageData} pageSize={knowledgeSize} setPage={setKnowledgePage} setPageSize={setKnowledgeSize} loading={loading} sizes={[8, 10, 20, 50]} />
        </section>
      )}

      {activeTab === 'audit' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>管理员审计日志</h2>
              <p>记录删除用户、删除饮食记录、编辑知识库等高风险后台操作。</p>
            </div>
          </div>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>ID</th><th>管理员</th><th>操作</th><th>对象</th><th>详情</th><th>时间</th></tr>
              </thead>
              <tbody>
                {auditLogs.length ? auditLogs.map(log => (
                  <tr key={log.id}>
                    <td>{log.id}</td>
                    <td>{log.adminUsername || 'unknown'}</td>
                    <td>{log.action}</td>
                    <td>{log.targetType || '-'} {log.targetId ? `#${log.targetId}` : ''}</td>
                    <td>{log.detail || '暂无'}</td>
                    <td>{formatTime(log.createdAt)}</td>
                  </tr>
                )) : <tr><td colSpan="6" className="empty-cell">暂无审计日志</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={auditPageData} pageSize={auditSize} setPage={setAuditPage} setPageSize={setAuditSize} loading={loading} sizes={[10, 20, 50]} />
        </section>
      )}

      {viewingChunk && <KnowledgeModal chunk={viewingChunk} onClose={() => setViewingChunk(null)} />}
    </div>
  )
}

function MetricCard({ value, label }) {
  return <div className="admin-card"><div className="admin-number">{value}</div><div className="admin-label">{label}</div></div>
}

function MetricSmall({ label, value }) {
  return <div className="meal-stat-card"><span>{label}</span><strong>{value}</strong></div>
}

function Info({ label, value }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}

function Chart({ title, items, empty, wide }) {
  return <div className={wide ? 'meal-chart-card wide' : 'meal-chart-card'}><h3>{title}</h3><MiniBars items={items || []} emptyText={empty} /></div>
}

function FoodTags({ names = [] }) {
  return (
    <div className="food-tag-list">
      {names.length ? names.map((name, index) => <span className="food-tag" key={`${name}-${index}`}>{name}</span>) : <span className="muted-text">暂无</span>}
    </div>
  )
}

function Pagination({ pageData, pageSize, setPage, setPageSize, loading, sizes = [10, 20, 50] }) {
  return (
    <div className="pagination">
      <button className="secondary-btn" type="button" onClick={() => setPage(page => Math.max(0, page - 1))} disabled={pageData.page <= 0 || loading}>上一页</button>
      <span>第 {(pageData.page ?? 0) + 1} / {Math.max(1, pageData.totalPages || 1)} 页，共 {pageData.totalElements || 0} 条</span>
      <button className="secondary-btn" type="button" onClick={() => setPage(page => page + 1)} disabled={(pageData.page ?? 0) + 1 >= (pageData.totalPages || 1) || loading}>下一页</button>
      <select value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}>
        {sizes.map(size => <option key={size} value={size}>{size} 条/页</option>)}
      </select>
    </div>
  )
}

function KnowledgeModal({ chunk, onClose }) {
  return (
    <div className="modal-backdrop">
      <section className="modal-card knowledge-modal">
        <header className="modal-head">
          <div>
            <h2>{chunk.title || `知识片段 ${chunk.id}`}</h2>
            <p>{chunk.source === 'admin' ? '管理员添加' : '系统 Markdown'} · {categoryLabel(chunk.category)} · 命中 {chunk.hitCount || 0} 次 · 最近引用：{formatTime(chunk.lastHitAt)}</p>
          </div>
          <button className="secondary-btn" type="button" onClick={onClose}>关闭</button>
        </header>
        <div className="knowledge-full-content">{chunk.content || chunk.contentPreview || '暂无内容'}</div>
      </section>
    </div>
  )
}
