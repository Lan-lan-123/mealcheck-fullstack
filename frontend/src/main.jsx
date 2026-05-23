import React, { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Camera, LogOut, ShieldCheck, Utensils } from 'lucide-react'
import {
  analyzeMeal,
  captcha,
  clearSession,
  currentGoal,
  deleteMeal,
  getUser,
  latestWeeklyReport,
  listMeals,
  login,
  logout,
  mealTrends,
  register,
  setSession,
  updateCurrentGoal
} from './api/client'
import AssistantCard from './components/AssistantCard'
import Empty from './components/Empty'
import RecordItem from './components/RecordItem'
import ReportView from './components/ReportView'
import ResultView from './components/ResultView'
import TrendCard from './components/TrendCard'
import WeeklyReportModal from './components/WeeklyReportModal'
import AdminPage from './pages/AdminPage'
import './styles.css'

const goals = [
  { value: 'current', label: '使用当前目标' },
  { value: 'balanced', label: '均衡饮食' },
  { value: 'fat_loss', label: '减脂目标' },
  { value: 'muscle_gain', label: '增肌目标' },
  { value: 'light', label: '清淡饮食' }
]

const defaultRecordFilters = {
  goal: '',
  from: '',
  to: '',
  minScore: '',
  maxScore: ''
}

function compactFilters(filters) {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== undefined && value !== null && value !== ''))
}

function emptyPage(size = 5) {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 }
}

function normalizePage(data, size = 5) {
  if (Array.isArray(data)) {
    return { content: data, page: 0, size: data.length || size, totalElements: data.length, totalPages: data.length ? 1 : 0 }
  }
  return data || emptyPage(size)
}

function isAdmin(user) {
  return user?.role === 'ADMIN'
}

function defaultPageFor(user) {
  return isAdmin(user) ? 'admin' : 'dashboard'
}

function AuthPage({ onAuthed }) {
  const [mode, setMode] = useState('login')
  const [loginRole, setLoginRole] = useState('USER')
  const [form, setForm] = useState({
    username: 'demo',
    password: '123456',
    displayName: 'Demo 用户',
    captchaAnswer: ''
  })
  const [captchaData, setCaptchaData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function refreshCaptcha() {
    const data = await captcha()
    setCaptchaData(data)
    setForm(current => ({ ...current, captchaAnswer: '' }))
  }

  useEffect(() => {
    refreshCaptcha().catch(err => setError(err.message))
  }, [])

  function switchRole(role) {
    setLoginRole(role)
    setMode('login')
    setForm(current => ({
      ...current,
      username: role === 'ADMIN' ? 'admin' : 'demo',
      displayName: role === 'ADMIN' ? '管理员' : 'Demo 用户',
      captchaAnswer: ''
    }))
    refreshCaptcha().catch(err => setError(err.message))
  }

  async function submit(e) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const auth = mode === 'login'
        ? await login({
            username: form.username,
            password: form.password,
            expectedRole: loginRole,
            captchaId: captchaData?.captchaId,
            captchaAnswer: form.captchaAnswer
          })
        : await register({
            username: form.username,
            password: form.password,
            displayName: form.displayName
          })
      setSession(auth)
      onAuthed(auth)
    } catch (err) {
      setError(err.message)
      if (mode === 'login') {
        refreshCaptcha().catch(() => {})
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand">
          <Utensils size={34} />
          <div>
            <h1>MealCheck</h1>
            <p>校园食堂饮食结构识别与智能建议系统</p>
          </div>
        </div>

        <div className="role-tabs">
          <button className={loginRole === 'USER' ? 'active' : ''} onClick={() => switchRole('USER')} type="button">
            普通用户
          </button>
          <button className={loginRole === 'ADMIN' ? 'active' : ''} onClick={() => switchRole('ADMIN')} type="button">
            管理员
          </button>
        </div>

        <div className="tabs">
          <button className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')} type="button">
            登录
          </button>
          {loginRole === 'USER' && (
            <button className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')} type="button">
              注册
            </button>
          )}
        </div>

        <form onSubmit={submit}>
          <label>
            用户名
            <input value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} />
          </label>
          <label>
            密码
            <input type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} />
          </label>
          {mode === 'register' && (
            <label>
              昵称
              <input value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} />
            </label>
          )}
          {mode === 'login' && (
            <label>
              验证码
              <div className="captcha-row">
                <span>{captchaData?.question || '加载中...'}</span>
                <input value={form.captchaAnswer} onChange={e => setForm({ ...form, captchaAnswer: e.target.value })} placeholder="答案" />
                <button type="button" onClick={refreshCaptcha}>换一题</button>
              </div>
            </label>
          )}
          {error && <p className="error">{error}</p>}
          <button className="primary" disabled={loading}>
            {loading ? '处理中...' : mode === 'login' ? `${loginRole === 'ADMIN' ? '管理员' : '用户'}登录` : '创建账号'}
          </button>
        </form>

        <p className="hint">管理员账号只能从管理员入口登录，普通用户登录后进入图片上传分析页面。</p>
      </section>
    </main>
  )
}

function App() {
  const [user, setUser] = useState(getUser())
  const [page, setPage] = useState(() => defaultPageFor(getUser()))

  function handleAuthed(auth) {
    const nextUser = {
      username: auth.username,
      displayName: auth.displayName,
      role: auth.role || 'USER'
    }
    setUser(nextUser)
    setPage(defaultPageFor(nextUser))
  }

  async function handleLogout() {
    try {
      await logout()
      clearSession()
      setUser(null)
      setPage('dashboard')
    } catch (err) {
      window.alert(err.message || '退出失败，请稍后重试')
    }
  }

  useEffect(() => {
    if (user && page === 'admin' && !isAdmin(user)) {
      setPage('dashboard')
    }
  }, [user, page])

  if (!user) {
    return <AuthPage onAuthed={handleAuthed} />
  }

  if (page === 'admin' && isAdmin(user)) {
    return <AdminPage onBack={() => setPage('dashboard')} onLogout={handleLogout} />
  }

  return (
    <Dashboard
      user={user}
      onLogout={handleLogout}
      onOpenAdmin={() => {
        if (isAdmin(user)) setPage('admin')
      }}
    />
  )
}

function Dashboard({ user, onLogout, onOpenAdmin }) {
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState('')
  const [goal, setGoal] = useState('current')
  const [goalProfile, setGoalProfile] = useState(null)
  const [goalDraft, setGoalDraft] = useState({ goalType: 'balanced', endDate: '', note: '' })
  const [result, setResult] = useState(null)
  const [recordPageData, setRecordPageData] = useState(emptyPage())
  const [recordPage, setRecordPage] = useState(0)
  const [recordSize, setRecordSize] = useState(5)
  const [recordFiltersDraft, setRecordFiltersDraft] = useState(defaultRecordFilters)
  const [recordFilters, setRecordFilters] = useState(defaultRecordFilters)
  const [report, setReport] = useState(null)
  const [trend, setTrend] = useState(null)
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [showWeeklyDetail, setShowWeeklyDetail] = useState(false)
  const [activeSection, setActiveSection] = useState('overview')

  async function refresh() {
    const [m, r, t, g] = await Promise.all([
      listMeals({ page: recordPage, size: recordSize, ...compactFilters(recordFilters) }),
      latestWeeklyReport(),
      mealTrends(30),
      currentGoal()
    ])
    setRecordPageData(normalizePage(m, recordSize))
    setReport(r)
    setTrend(t)
    setGoalProfile(g)
    if (g?.goalType) {
      setGoalDraft({
        goalType: g.goalType,
        endDate: g.endDate || '',
        note: g.note || ''
      })
    }
  }

  useEffect(() => {
    refresh().catch(err => setMessage(err.message))
  }, [recordPage, recordSize, recordFilters])

  function onFileChange(e) {
    const picked = e.target.files?.[0]
    setFile(picked)
    setPreview(picked ? URL.createObjectURL(picked) : '')
  }

  async function analyze() {
    if (!file) {
      setMessage('请先选择一张饮食图片。')
      return
    }
    setLoading(true)
    setMessage('')
    try {
      const data = await analyzeMeal(file, goal)
      setResult(data)
      setRecordPage(0)
      await refresh()
    } catch (err) {
      setMessage(err.message)
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteRecord(id) {
    if (!window.confirm('确认删除这条饮食记录吗？相关图片也会一起删除。')) return
    setLoading(true)
    setMessage('')
    try {
      await deleteMeal(id)
      setMessage('饮食记录已删除。')
      await refresh()
    } catch (err) {
      setMessage(err.message || '删除饮食记录失败。')
    } finally {
      setLoading(false)
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
    setLoading(true)
    setMessage('')
    try {
      const nextGoal = await updateCurrentGoal({
        goalType: goalDraft.goalType,
        endDate: goalDraft.endDate || null,
        note: goalDraft.note
      })
      setGoalProfile(nextGoal)
      setMessage('当前目标已更新')
    } catch (err) {
      setMessage(err.message || '更新目标失败')
    } finally {
      setLoading(false)
    }
  }

  const avgLabel = useMemo(() => (report ? `${report.averageScore || 0}` : '--'), [report])
  const goalLabel = goals.find(item => item.value === goalProfile?.goalType)?.label || '均衡饮食'
  const weeklyRecords = useMemo(() => {
    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000
    return (recordPageData.content || []).filter(r => new Date(r.createdAt).getTime() >= cutoff)
  }, [recordPageData.content])
  const records = recordPageData.content || []

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand compact">
          <Utensils />
          <div>
            <h1>MealCheck</h1>
            <p>AI 饮食识别、评分、RAG 建议与趋势分析</p>
          </div>
        </div>
        <div className="userbox">
          {isAdmin(user) && (
            <button onClick={onOpenAdmin} type="button">
              <ShieldCheck size={16} />
              管理后台
            </button>
          )}
          <span>{user.displayName || user.username}</span>
          <button onClick={onLogout} type="button">
            <LogOut size={16} />
            退出
          </button>
        </div>
      </header>

      {message && <div className="toast">{message}</div>}

      <section className="dashboard-page">
        <nav className="dashboard-tabs" aria-label="用户页面切换">
          <button className={activeSection === 'overview' ? 'active' : ''} onClick={() => setActiveSection('overview')} type="button">
            概览分析
          </button>
          <button className={activeSection === 'records' ? 'active' : ''} onClick={() => setActiveSection('records')} type="button">
            饮食记录
          </button>
        </nav>

        {activeSection === 'overview' ? (
          <>
            <section className="analysis-layout">
              <div className="card upload-card">
                <h2>
                  <Camera size={20} />
                  上传饮食图片
                </h2>
                <p>选择餐盘图片后，系统会识别食物、计算结构评分，并结合知识库生成建议。</p>
                <label className="file-picker">
                  <input type="file" accept="image/*" onChange={onFileChange} />
                  {preview ? <img src={preview} alt="preview" /> : <span>选择图片</span>}
                </label>
                <div className="upload-actions">
                  <select value={goal} onChange={e => setGoal(e.target.value)}>
                    {goals.map(g => (
                      <option key={g.value} value={g.value}>
                        {g.label}
                      </option>
                    ))}
                  </select>
                  <button className="primary" onClick={analyze} disabled={loading}>
                    {loading ? '分析中...' : '开始分析'}
                  </button>
                </div>
                <form className="goal-form" onSubmit={saveGoal}>
                  <div>
                    <strong>当前目标</strong>
                    <span>{goalLabel}</span>
                  </div>
                  <select value={goalDraft.goalType} onChange={e => setGoalDraft({ ...goalDraft, goalType: e.target.value })}>
                    {goals.filter(item => item.value !== 'current').map(g => (
                      <option key={g.value} value={g.value}>{g.label}</option>
                    ))}
                  </select>
                  <input type="date" value={goalDraft.endDate} onChange={e => setGoalDraft({ ...goalDraft, endDate: e.target.value })} />
                  <input value={goalDraft.note} onChange={e => setGoalDraft({ ...goalDraft, note: e.target.value })} placeholder="目标说明" />
                  <button className="secondary-btn" type="submit" disabled={loading}>保存目标</button>
                </form>
              </div>

              <div className="card score-card">
                <h2>
                  <ShieldCheck size={20} />
                  本次分析结果
                </h2>
                {result ? <ResultView result={result} /> : <Empty text="上传图片后，这里会显示评分、食物识别、风险标签和饮食建议。" />}
              </div>
            </section>

            <section className="insight-layout">
              {report ? <ReportView report={report} avgLabel={avgLabel} onOpenDetail={() => setShowWeeklyDetail(true)} /> : <div className="card"><Empty text="暂无周报数据" /></div>}
              <TrendCard trend={trend} />
            </section>
          </>
        ) : (
          <div className="card history-card records-page">
            <div className="section-title-row">
              <div>
                <h2>最近饮食记录</h2>
                <p>按目标、日期和评分筛选历史记录。</p>
              </div>
              <span>{recordPageData.totalElements || 0} 条</span>
            </div>
            <form className="record-filter-form" onSubmit={applyRecordFilters}>
              <select value={recordFiltersDraft.goal} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, goal: e.target.value })}>
                <option value="">全部目标</option>
                {goals.map(g => (
                  <option key={g.value} value={g.value}>
                    {g.label}
                  </option>
                ))}
              </select>
              <input type="date" value={recordFiltersDraft.from} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, from: e.target.value })} />
              <input type="date" value={recordFiltersDraft.to} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, to: e.target.value })} />
              <input type="number" min="0" max="100" placeholder="最低评分" value={recordFiltersDraft.minScore} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, minScore: e.target.value })} />
              <input type="number" min="0" max="100" placeholder="最高评分" value={recordFiltersDraft.maxScore} onChange={e => setRecordFiltersDraft({ ...recordFiltersDraft, maxScore: e.target.value })} />
              <button className="secondary-btn" type="submit" disabled={loading}>筛选</button>
              <button className="secondary-btn" type="button" onClick={resetRecordFilters} disabled={loading}>重置</button>
            </form>

            <div className="record-list">
              {records.length ? records.map(r => <RecordItem key={r.id} record={r} onDelete={handleDeleteRecord} />) : <Empty text="暂无饮食记录" />}
            </div>
            <RecordPagination pageData={recordPageData} pageSize={recordSize} setPage={setRecordPage} setPageSize={setRecordSize} loading={loading} />
          </div>
        )}

        <AssistantCard onError={setMessage} />
      </section>

      {showWeeklyDetail && <WeeklyReportModal report={report} avgLabel={avgLabel} records={weeklyRecords} onClose={() => setShowWeeklyDetail(false)} />}
    </main>
  )
}

function RecordPagination({ pageData, pageSize, setPage, setPageSize, loading }) {
  if ((pageData.totalElements || 0) <= 0) return null
  return (
    <div className="pagination compact-pagination">
      <button className="secondary-btn" type="button" onClick={() => setPage(page => Math.max(0, page - 1))} disabled={pageData.page <= 0 || loading}>
        上一页
      </button>
      <span>第 {(pageData.page ?? 0) + 1} / {Math.max(1, pageData.totalPages || 1)} 页，共 {pageData.totalElements || 0} 条</span>
      <button className="secondary-btn" type="button" onClick={() => setPage(page => page + 1)} disabled={(pageData.page ?? 0) + 1 >= (pageData.totalPages || 1) || loading}>
        下一页
      </button>
      <select value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setPage(0) }}>
        <option value="5">5 条/页</option>
        <option value="10">10 条/页</option>
        <option value="20">20 条/页</option>
      </select>
    </div>
  )
}

createRoot(document.getElementById('root')).render(<App />)
