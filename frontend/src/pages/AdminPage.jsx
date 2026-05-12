import { useEffect, useMemo, useState } from 'react'
import {
  adminAddKnowledgeChunk,
  adminDashboard,
  adminDeleteKnowledgeChunk,
  adminDeleteMeal,
  adminDeleteUser,
  adminKnowledgeChunks,
  adminMeals,
  adminReindexKnowledge,
  adminUpdateKnowledgeChunk,
  adminUsers,
  getUser
} from '../api/client'

function formatTime(value) {
  if (!value) return '无'
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
  const count =
    typeof result === 'number'
      ? result
      : result?.chunks ?? result?.count ?? result?.total ?? result?.indexedCount

  return count === undefined || count === null
    ? 'RAG 知识库已重建'
    : `RAG 知识库已重建，共 ${count} 条`
}

function countTop(values, limit) {
  const counts = new Map()
  values
    .filter(value => value !== undefined && value !== null && String(value).trim())
    .forEach(value => {
      const key = String(value).trim()
      counts.set(key, (counts.get(key) || 0) + 1)
    })

  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([label, value]) => ({ label, value }))
}

function MiniBars({ items, emptyText }) {
  const max = Math.max(1, ...items.map(item => item.value))

  if (!items.length) {
    return <p className="empty-mini">{emptyText}</p>
  }

  return (
    <div className="mini-bars">
      {items.map(item => (
        <div className="mini-bar-row" key={item.label}>
          <span>{item.label}</span>
          <div className="mini-bar-track">
            <i style={{ width: `${Math.max(8, (item.value / max) * 100)}%` }} />
          </div>
          <strong>{item.value}</strong>
        </div>
      ))}
    </div>
  )
}

const defaultMealFilters = {
  username: '',
  from: '',
  to: '',
  minScore: '',
  maxScore: ''
}

export default function AdminPage({ onBack }) {
  const [dashboard, setDashboard] = useState(null)
  const [users, setUsers] = useState([])
  const [mealPageData, setMealPageData] = useState({
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0
  })
  const [chunks, setChunks] = useState([])

  const [activeTab, setActiveTab] = useState('overview')
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  const [deletingUserId, setDeletingUserId] = useState(null)
  const [showMealManager, setShowMealManager] = useState(false)
  const [selectedMealIds, setSelectedMealIds] = useState([])
  const [deletingMealId, setDeletingMealId] = useState(null)
  const [mealPage, setMealPage] = useState(0)
  const [mealSize, setMealSize] = useState(20)
  const [mealFiltersDraft, setMealFiltersDraft] = useState(defaultMealFilters)
  const [mealFilters, setMealFilters] = useState(defaultMealFilters)

  const [knowledgeForm, setKnowledgeForm] = useState({ title: '', content: '' })
  const [knowledgeSearchDraft, setKnowledgeSearchDraft] = useState('')
  const [knowledgeSearch, setKnowledgeSearch] = useState('')
  const [editingChunkId, setEditingChunkId] = useState(null)
  const [editingChunkForm, setEditingChunkForm] = useState({ title: '', content: '' })
  const [deletingChunkId, setDeletingChunkId] = useState(null)

  const meals = mealPageData.content || []
  const overview = dashboard?.overview || {}
  const system = dashboard?.system || {}
  const currentUser = getUser()

  async function loadAdminData() {
    setLoading(true)
    setError('')

    try {
      const [dashboardData, userData, mealData, chunkData] = await Promise.all([
        adminDashboard(),
        adminUsers(),
        adminMeals({
          page: mealPage,
          size: mealSize,
          ...mealFilters
        }),
        adminKnowledgeChunks({ title: knowledgeSearch })
      ])

      const nextMealPage = Array.isArray(mealData)
        ? {
            content: mealData,
            page: 0,
            size: mealData.length,
            totalElements: mealData.length,
            totalPages: mealData.length ? 1 : 0
          }
        : mealData

      setDashboard(dashboardData)
      setUsers(Array.isArray(userData) ? userData : [])
      setMealPageData(nextMealPage)
      setSelectedMealIds(ids =>
        ids.filter(id => (nextMealPage.content || []).some(meal => meal.id === id))
      )
      setChunks(Array.isArray(chunkData) ? chunkData : [])
    } catch (err) {
      setError(err.message || '管理员数据加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAdminData()
  }, [mealPage, mealSize, mealFilters, knowledgeSearch])

  const mealAnalytics = useMemo(() => {
    const scores = meals
      .map(meal => Number(meal.score))
      .filter(score => Number.isFinite(score))
    const averageScore = scores.length
      ? Math.round(scores.reduce((sum, score) => sum + score, 0) / scores.length)
      : 0

    return {
      total: mealPageData.totalElements ?? meals.length,
      pageTotal: meals.length,
      averageScore,
      scoreBands: [
        { label: '80分以上', value: scores.filter(score => score >= 80).length },
        { label: '60-79分', value: scores.filter(score => score >= 60 && score < 80).length },
        { label: '60分以下', value: scores.filter(score => score < 60).length }
      ],
      userCounts: countTop(meals.map(meal => meal.username || '未知用户'), 6),
      foodCounts: countTop(meals.flatMap(meal => meal.foodNames || []), 8)
    }
  }, [mealPageData.totalElements, meals])

  async function handleReindex() {
    setLoading(true)
    setError('')
    setMessage('')

    try {
      const result = await adminReindexKnowledge()
      await loadAdminData()
      setMessage(`${getReindexMessage(result)}，管理员手动添加的片段已保留`)
    } catch (err) {
      setError(err.message || 'RAG 知识库重建失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteUser(user) {
    if (!window.confirm(`确定删除用户 ${user.username} 吗？该用户的餐食记录和上传图片也会被删除。`)) {
      return
    }

    setDeletingUserId(user.id)
    setError('')
    setMessage('')

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

  function handleMealFilterSubmit(e) {
    e.preventDefault()
    setMealPage(0)
    setMealFilters({ ...mealFiltersDraft })
  }

  function resetMealFilters() {
    setMealFiltersDraft(defaultMealFilters)
    setMealFilters(defaultMealFilters)
    setMealPage(0)
  }

  function toggleMealSelection(id) {
    setSelectedMealIds(ids =>
      ids.includes(id) ? ids.filter(item => item !== id) : [...ids, id]
    )
  }

  function toggleAllMealSelection() {
    if (selectedMealIds.length === meals.length) {
      setSelectedMealIds([])
    } else {
      setSelectedMealIds(meals.map(meal => meal.id))
    }
  }

  async function handleDeleteMeal(meal) {
    if (!window.confirm(`确定删除饮食记录 #${meal.id} 吗？`)) return

    setDeletingMealId(meal.id)
    setError('')
    setMessage('')

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
    if (selectedMealIds.length === 0) return
    if (!window.confirm(`确定删除选中的 ${selectedMealIds.length} 条饮食记录吗？`)) return

    setLoading(true)
    setError('')
    setMessage('')

    try {
      const count = selectedMealIds.length
      await Promise.all(selectedMealIds.map(id => adminDeleteMeal(id)))
      setSelectedMealIds([])
      await loadAdminData()
      setMessage(`已删除 ${count} 条饮食记录`)
    } catch (err) {
      setError(err.message || '批量删除饮食记录失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleAddKnowledgeChunk(e) {
    e.preventDefault()
    setLoading(true)
    setError('')
    setMessage('')

    try {
      await adminAddKnowledgeChunk(knowledgeForm)
      setKnowledgeForm({ title: '', content: '' })
      await loadAdminData()
      setMessage('已新增 RAG 知识片段')
    } catch (err) {
      setError(err.message || '新增 RAG 知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  function startEditChunk(chunk) {
    setEditingChunkId(chunk.id)
    setEditingChunkForm({
      title: chunk.title || '',
      content: chunk.content || chunk.contentPreview || ''
    })
  }

  async function handleUpdateKnowledgeChunk(e) {
    e.preventDefault()
    if (!editingChunkId) return

    setLoading(true)
    setError('')
    setMessage('')

    try {
      await adminUpdateKnowledgeChunk(editingChunkId, editingChunkForm)
      setEditingChunkId(null)
      setEditingChunkForm({ title: '', content: '' })
      await loadAdminData()
      setMessage('已更新 RAG 知识片段')
    } catch (err) {
      setError(err.message || '更新 RAG 知识片段失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteKnowledgeChunk(chunk) {
    if (!window.confirm(`确定删除「${chunk.title || `知识片段 ${chunk.id}`}」吗？`)) return

    setDeletingChunkId(chunk.id)
    setError('')
    setMessage('')

    try {
      await adminDeleteKnowledgeChunk(chunk.id)
      await loadAdminData()
      setMessage('已删除 RAG 知识片段')
    } catch (err) {
      setError(err.message || '删除 RAG 知识片段失败')
    } finally {
      setDeletingChunkId(null)
    }
  }

  function handleKnowledgeSearch(e) {
    e.preventDefault()
    setKnowledgeSearch(knowledgeSearchDraft)
  }

  return (
    <div className="admin-page">
      <section className="admin-header">
        <div>
          <h1>管理后台</h1>
          <p>查看系统数据、用户、饮食记录和 RAG 知识库。</p>
        </div>

        <div className="admin-header-actions">
          <button className="secondary-btn" type="button" onClick={loadAdminData} disabled={loading}>
            刷新
          </button>
          <button className="secondary-btn" type="button" onClick={onBack}>
            返回用户页面
          </button>
        </div>
      </section>

      {error && <div className="admin-message error">{error}</div>}
      {message && <div className="admin-message success">{message}</div>}
      {loading && <div className="admin-message">正在加载管理员数据...</div>}

      <section className="admin-grid">
        <div className="admin-card">
          <div className="admin-number">{overview.userCount ?? 0}</div>
          <div className="admin-label">注册用户</div>
        </div>
        <div className="admin-card">
          <div className="admin-number">{overview.mealRecordCount ?? 0}</div>
          <div className="admin-label">饮食记录</div>
        </div>
        <div className="admin-card">
          <div className="admin-number">{overview.todayMealRecordCount ?? 0}</div>
          <div className="admin-label">今日提交</div>
        </div>
        <div className="admin-card">
          <div className="admin-number">{overview.knowledgeChunkCount ?? 0}</div>
          <div className="admin-label">RAG 知识片段</div>
        </div>
      </section>

      <section className="admin-tabs">
        <button type="button" className={activeTab === 'overview' ? 'active' : ''} onClick={() => setActiveTab('overview')}>
          系统概览
        </button>
        <button type="button" className={activeTab === 'users' ? 'active' : ''} onClick={() => setActiveTab('users')}>
          用户管理
        </button>
        <button type="button" className={activeTab === 'meals' ? 'active' : ''} onClick={() => setActiveTab('meals')}>
          饮食记录
        </button>
        <button type="button" className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => setActiveTab('knowledge')}>
          RAG 知识库
        </button>
      </section>

      {activeTab === 'overview' && (
        <section className="admin-section">
          <h2>系统信息</h2>
          <div className="admin-info-grid">
            <div>
              <span>后端</span>
              <strong>{system.backend || 'Spring Boot'}</strong>
            </div>
            <div>
              <span>数据库</span>
              <strong>{system.database || 'PostgreSQL'}</strong>
            </div>
            <div>
              <span>向量数据库</span>
              <strong>{system.vectorDatabase || 'pgvector'}</strong>
            </div>
            <div>
              <span>Embedding</span>
              <strong>{system.embeddingMethod || 'HashEmbeddingService'}</strong>
            </div>
            <div>
              <span>向量维度</span>
              <strong>{system.embeddingDimension || 384}</strong>
            </div>
            <div>
              <span>AI 模型</span>
              <strong>{system.aiModel || '未配置'}</strong>
            </div>
            <div>
              <span>API Key</span>
              <strong>{formatBool(system.apiKeyConfigured)}</strong>
            </div>
            <div>
              <span>Base URL</span>
              <strong>{formatBool(system.baseUrlConfigured)}</strong>
            </div>
          </div>

          <div className="admin-note">
            重建会刷新系统 Markdown 片段，并保留管理员手动添加的知识片段。
          </div>
        </section>
      )}

      {activeTab === 'users' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>用户管理</h2>
              <p>查看系统注册用户、角色、注册时间和最后上传时间。</p>
            </div>
          </div>

          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>用户名</th>
                  <th>昵称</th>
                  <th>角色</th>
                  <th>最后上传时间</th>
                  <th>注册时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {users.length === 0 ? (
                  <tr>
                    <td colSpan="7" className="empty-cell">暂无用户</td>
                  </tr>
                ) : (
                  users.map(user => (
                    <tr key={user.id}>
                      <td>{user.id}</td>
                      <td>{user.username}</td>
                      <td>{user.displayName || '无'}</td>
                      <td>
                        <span className={user.role === 'ADMIN' ? 'role-tag admin' : 'role-tag'}>
                          {user.role || 'USER'}
                        </span>
                      </td>
                      <td>{formatTime(user.lastUploadAt)}</td>
                      <td>{formatTime(user.createdAt)}</td>
                      <td>
                        <button
                          className="table-danger-btn"
                          type="button"
                          onClick={() => handleDeleteUser(user)}
                          disabled={loading || deletingUserId === user.id || user.username === currentUser?.username}
                        >
                          {deletingUserId === user.id ? '删除中...' : '删除'}
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {activeTab === 'meals' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>全站饮食记录</h2>
              <p>分页查看、筛选、分析和管理全体用户提交的饮食记录。</p>
            </div>
            <button
              className={showMealManager ? 'primary-btn' : 'secondary-btn'}
              type="button"
              onClick={() => setShowMealManager(value => !value)}
            >
              管理全体饮食记录
            </button>
          </div>

          {showMealManager && (
            <div className="meal-manager">
              <form className="filter-grid" onSubmit={handleMealFilterSubmit}>
                <input
                  value={mealFiltersDraft.username}
                  onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, username: e.target.value })}
                  placeholder="用户名"
                />
                <input
                  type="date"
                  value={mealFiltersDraft.from}
                  onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, from: e.target.value })}
                />
                <input
                  type="date"
                  value={mealFiltersDraft.to}
                  onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, to: e.target.value })}
                />
                <input
                  type="number"
                  min="0"
                  max="100"
                  value={mealFiltersDraft.minScore}
                  onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, minScore: e.target.value })}
                  placeholder="最低分"
                />
                <input
                  type="number"
                  min="0"
                  max="100"
                  value={mealFiltersDraft.maxScore}
                  onChange={e => setMealFiltersDraft({ ...mealFiltersDraft, maxScore: e.target.value })}
                  placeholder="最高分"
                />
                <button className="secondary-btn" type="submit">筛选</button>
                <button className="secondary-btn" type="button" onClick={resetMealFilters}>重置</button>
              </form>

              <div className="meal-analytics-grid">
                <div className="meal-stat-card">
                  <span>筛选结果</span>
                  <strong>{mealAnalytics.total}</strong>
                </div>
                <div className="meal-stat-card">
                  <span>当前页平均分</span>
                  <strong>{mealAnalytics.averageScore}</strong>
                </div>
                <div className="meal-chart-card">
                  <h3>当前页评分分布</h3>
                  <MiniBars items={mealAnalytics.scoreBands} emptyText="暂无评分数据" />
                </div>
                <div className="meal-chart-card">
                  <h3>当前页活跃用户</h3>
                  <MiniBars items={mealAnalytics.userCounts} emptyText="暂无用户数据" />
                </div>
                <div className="meal-chart-card wide">
                  <h3>当前页高频食物</h3>
                  <MiniBars items={mealAnalytics.foodCounts} emptyText="暂无食物数据" />
                </div>
              </div>

              <div className="meal-manager-actions">
                <button className="secondary-btn" type="button" onClick={toggleAllMealSelection}>
                  {selectedMealIds.length === meals.length && meals.length > 0 ? '取消全选' : '全选本页'}
                </button>
                <button
                  className="table-danger-btn"
                  type="button"
                  onClick={handleDeleteSelectedMeals}
                  disabled={selectedMealIds.length === 0 || loading}
                >
                  删除选中记录{selectedMealIds.length ? ` (${selectedMealIds.length})` : ''}
                </button>
              </div>
            </div>
          )}

          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  {showMealManager && <th>选择</th>}
                  <th>ID</th>
                  <th>用户</th>
                  <th>评分</th>
                  <th>识别食物</th>
                  <th>摘要</th>
                  <th>提交时间</th>
                  {showMealManager && <th>操作</th>}
                </tr>
              </thead>
              <tbody>
                {meals.length === 0 ? (
                  <tr>
                    <td colSpan={showMealManager ? 8 : 6} className="empty-cell">暂无饮食记录</td>
                  </tr>
                ) : (
                  meals.map(meal => (
                    <tr key={meal.id}>
                      {showMealManager && (
                        <td>
                          <input
                            type="checkbox"
                            checked={selectedMealIds.includes(meal.id)}
                            onChange={() => toggleMealSelection(meal.id)}
                          />
                        </td>
                      )}
                      <td>{meal.id}</td>
                      <td>{meal.username || '未知用户'}</td>
                      <td><strong>{meal.score ?? '无'}</strong></td>
                      <td>
                        <div className="food-tag-list">
                          {(meal.foodNames || []).length === 0 ? (
                            <span className="muted-text">暂无</span>
                          ) : (
                            meal.foodNames.map((name, index) => (
                              <span className="food-tag" key={`${meal.id}-${name}-${index}`}>{name}</span>
                            ))
                          )}
                        </div>
                      </td>
                      <td className="summary-cell">{meal.summary || '无'}</td>
                      <td>{formatTime(meal.createdAt)}</td>
                      {showMealManager && (
                        <td>
                          <button
                            className="table-danger-btn"
                            type="button"
                            onClick={() => handleDeleteMeal(meal)}
                            disabled={deletingMealId === meal.id || loading}
                          >
                            {deletingMealId === meal.id ? '删除中...' : '删除'}
                          </button>
                        </td>
                      )}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          <div className="pagination">
            <button
              className="secondary-btn"
              type="button"
              onClick={() => setMealPage(page => Math.max(0, page - 1))}
              disabled={mealPageData.page <= 0 || loading}
            >
              上一页
            </button>
            <span>
              第 {(mealPageData.page ?? 0) + 1} / {Math.max(1, mealPageData.totalPages || 1)} 页，共 {mealPageData.totalElements || 0} 条
            </span>
            <button
              className="secondary-btn"
              type="button"
              onClick={() => setMealPage(page => page + 1)}
              disabled={(mealPageData.page ?? 0) + 1 >= (mealPageData.totalPages || 1) || loading}
            >
              下一页
            </button>
            <select
              value={mealSize}
              onChange={e => {
                setMealSize(Number(e.target.value))
                setMealPage(0)
              }}
            >
              <option value="10">10 条/页</option>
              <option value="20">20 条/页</option>
              <option value="50">50 条/页</option>
            </select>
          </div>
        </section>
      )}

      {activeTab === 'knowledge' && (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>RAG 知识库维护</h2>
              <p>管理系统 Markdown 片段和管理员手动添加的知识片段。</p>
            </div>

            <button className="primary-btn" type="button" onClick={handleReindex} disabled={loading}>
              重建系统 RAG（保留手动添加）
            </button>
          </div>

          <div className="admin-note">
            重建只刷新系统 Markdown 来源，不删除管理员手动添加的片段。
          </div>

          <form className="filter-grid" onSubmit={handleKnowledgeSearch}>
            <input
              value={knowledgeSearchDraft}
              onChange={e => setKnowledgeSearchDraft(e.target.value)}
              placeholder="按标题搜索"
            />
            <button className="secondary-btn" type="submit">搜索</button>
            <button
              className="secondary-btn"
              type="button"
              onClick={() => {
                setKnowledgeSearchDraft('')
                setKnowledgeSearch('')
              }}
            >
              清空
            </button>
          </form>

          <form className="knowledge-form" onSubmit={handleAddKnowledgeChunk}>
            <input
              value={knowledgeForm.title}
              onChange={e => setKnowledgeForm({ ...knowledgeForm, title: e.target.value })}
              placeholder="知识库标题"
            />
            <textarea
              value={knowledgeForm.content}
              onChange={e => setKnowledgeForm({ ...knowledgeForm, content: e.target.value })}
              placeholder="知识库内容"
              rows="4"
            />
            <button className="secondary-btn" type="submit" disabled={loading}>
              增加 RAG 知识库
            </button>
          </form>

          <div className="knowledge-list">
            {chunks.length === 0 ? (
              <div className="empty-box">暂无 RAG 知识片段</div>
            ) : (
              chunks.map(chunk => (
                <article className="knowledge-card" key={chunk.id}>
                  {editingChunkId === chunk.id ? (
                    <form className="knowledge-edit-form" onSubmit={handleUpdateKnowledgeChunk}>
                      <input
                        value={editingChunkForm.title}
                        onChange={e => setEditingChunkForm({ ...editingChunkForm, title: e.target.value })}
                      />
                      <textarea
                        value={editingChunkForm.content}
                        onChange={e => setEditingChunkForm({ ...editingChunkForm, content: e.target.value })}
                        rows="5"
                      />
                      <div className="knowledge-card-actions">
                        <button className="secondary-btn" type="submit" disabled={loading}>保存</button>
                        <button
                          className="secondary-btn"
                          type="button"
                          onClick={() => setEditingChunkId(null)}
                        >
                          取消
                        </button>
                      </div>
                    </form>
                  ) : (
                    <>
                      <div className="knowledge-card-head">
                        <h3>{chunk.title || `知识片段 ${chunk.id}`}</h3>
                        <span>{chunk.contentLength ?? 0} 字</span>
                      </div>
                      <div className="knowledge-meta">
                        <span className={chunk.source === 'admin' ? 'source-tag admin' : 'source-tag'}>
                          {chunk.source === 'admin' ? '管理员手动添加' : '系统 Markdown'}
                        </span>
                        <span>命中 {chunk.hitCount || 0} 次</span>
                        <span>最近命中 {formatTime(chunk.lastHitAt)}</span>
                      </div>
                      <p>{chunk.contentPreview || '暂无内容摘要'}</p>
                      <div className="knowledge-card-actions">
                        <button className="secondary-btn" type="button" onClick={() => startEditChunk(chunk)}>
                          编辑
                        </button>
                        <button
                          className="table-danger-btn"
                          type="button"
                          onClick={() => handleDeleteKnowledgeChunk(chunk)}
                          disabled={deletingChunkId === chunk.id}
                        >
                          {deletingChunkId === chunk.id ? '删除中...' : '删除当前 RAG 知识库'}
                        </button>
                      </div>
                    </>
                  )}
                </article>
              ))
            )}
          </div>
        </section>
      )}
    </div>
  )
}
