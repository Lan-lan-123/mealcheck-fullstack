import React, { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Camera, LogOut, ShieldCheck, Utensils } from 'lucide-react'
import {
  analyzeMeal,
  clearSession,
  getToken,
  getUser,
  listMeals,
  login,
  register,
  setSession,
  weeklyReport,
  deleteMeal
} from './api/client'
import AdminPage from './pages/AdminPage'
import './styles.css'

const goals = [
  { value: 'balanced', label: '均衡饮食' },
  { value: 'fat_loss', label: '减脂目标' },
  { value: 'muscle_gain', label: '增肌目标' },
  { value: 'light', label: '清淡饮食' }
]

function AuthPage({ onAuthed }) {
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState({
    username: 'demo',
    password: '123456',
    displayName: 'Demo 用户'
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function submit(e) {
    e.preventDefault()
    setLoading(true)
    setError('')

    try {
      const auth = mode === 'login' ? await login(form) : await register(form)
      setSession(auth)
      onAuthed(auth)
    } catch (err) {
      setError(err.message)
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
            <p>饮食结构评估系统</p>
          </div>
        </div>

        <div className="tabs">
          <button
            className={mode === 'login' ? 'active' : ''}
            onClick={() => setMode('login')}
            type="button"
          >
            登录
          </button>

          <button
            className={mode === 'register' ? 'active' : ''}
            onClick={() => setMode('register')}
            type="button"
          >
            注册
          </button>
        </div>

        <form onSubmit={submit}>
          <label>
            用户名
            <input
              value={form.username}
              onChange={e => setForm({ ...form, username: e.target.value })}
            />
          </label>

          <label>
            密码
            <input
              type="password"
              value={form.password}
              onChange={e => setForm({ ...form, password: e.target.value })}
            />
          </label>

          {mode === 'register' && (
            <label>
              昵称
              <input
                value={form.displayName}
                onChange={e => setForm({ ...form, displayName: e.target.value })}
              />
            </label>
          )}

          {error && <p className="error">{error}</p>}

          <button className="primary" disabled={loading}>
            {loading ? '处理中...' : mode === 'login' ? '登录系统' : '创建账号'}
          </button>
        </form>

        <p className="hint">
          首次使用可切换到注册；若后端未配置 API Key，系统会自动进入演示识别模式。
        </p>
      </section>
    </main>
  )
}

function App() {
  const [user, setUser] = useState(getUser())
  const [page, setPage] = useState('dashboard')

  function handleAuthed(auth) {
    setUser({
      username: auth.username,
      displayName: auth.displayName,
      role: auth.role || 'USER'
    })
    setPage('dashboard')
  }

  function handleLogout() {
    clearSession()
    setUser(null)
    setPage('dashboard')
  }

  if (!user) {
    return <AuthPage onAuthed={handleAuthed} />
  }

  if (page === 'admin') {
    return <AdminPage onBack={() => setPage('dashboard')} />
  }

  return (
    <Dashboard
      user={user}
      onLogout={handleLogout}
      onOpenAdmin={() => setPage('admin')}
    />
  )
}

function Dashboard({ user, onLogout, onOpenAdmin }) {
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState('')
  const [goal, setGoal] = useState('balanced')
  const [result, setResult] = useState(null)
  const [records, setRecords] = useState([])
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [showWeeklyDetail, setShowWeeklyDetail] = useState(false)

  async function refresh() {
    const [m, r] = await Promise.all([listMeals(), weeklyReport(7)])
    setRecords(m)
    setReport(r)
  }

  useEffect(() => {
    refresh().catch(err => setMessage(err.message))
  }, [])

  function onFileChange(e) {
    const picked = e.target.files?.[0]
    setFile(picked)
    setPreview(picked ? URL.createObjectURL(picked) : '')
  }

  async function analyze() {
    if (!file) {
      setMessage('请先选择一张饭菜图片')
      return
    }

    setLoading(true)
    setMessage('')

    try {
      const data = await analyzeMeal(file, goal)
      setResult(data)
      await refresh()
    } catch (err) {
      setMessage(err.message)
    } finally {
      setLoading(false)
    }
  }

  async function handleDeleteRecord(id) {
    const ok = window.confirm('确定要删除这条饮食记录吗？')
    if (!ok) return

    setLoading(true)
    setMessage('')

    try {
      await deleteMeal(id)
      setMessage('记录已删除')
      await refresh()
    } catch (err) {
      setMessage(err.message || '删除失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  const avgLabel = useMemo(() => {
    return report ? `${report.averageScore || 0}` : '--'
  }, [report])

  const weeklyRecords = useMemo(() => {
    const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000
    return records.filter(r => new Date(r.createdAt).getTime() >= cutoff)
  }, [records])

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand compact">
          <Utensils />
          <div>
            <h1>MealCheck</h1>
            <p>拍照式饮食结构评估系统</p>
          </div>
        </div>

        <div className="userbox">
          {user?.role === 'ADMIN' && (
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

      <section className="grid">
        <div className="card upload-card">
          <h2>
            <Camera size={20} />
            上传饭菜图片
          </h2>

          <p>系统评估饮食结构，接入 Qwen系列VL API</p>

          <label className="file-picker">
            <input type="file" accept="image/*" onChange={onFileChange} />
            {preview ? <img src={preview} alt="preview" /> : <span>点击选择图片</span>}
          </label>

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

        <div className="card score-card">
          <h2>
            <ShieldCheck size={20} />
            本餐分析结果
          </h2>

          {result ? (
            <ResultView result={result} />
          ) : (
            <Empty text="上传图片后，这里会显示识别食物、结构评分、风险标签和饮食建议。" />
          )}
        </div>

        {report ? (
          <ReportView
            report={report}
            avgLabel={avgLabel}
            onOpenDetail={() => setShowWeeklyDetail(true)}
          />
        ) : (
          <Empty text="暂无周报" />
        )}

        <div className="card history-card">
          <h2>最近记录</h2>

          {records.length ? (
            records.map(r => (
              <RecordItem
                key={r.id}
                record={r}
                onDelete={handleDeleteRecord}
              />
            ))
          ) : (
            <Empty text="还没有饮食记录" />
          )}
        </div>
      </section>

      {showWeeklyDetail && (
        <WeeklyReportModal
          report={report}
          avgLabel={avgLabel}
          records={weeklyRecords}
          onClose={() => setShowWeeklyDetail(false)}
        />
      )}
    </main>
  )
}

function ResultView({ result }) {
  const evaluation = result?.evaluation || {}
  const recognition = result?.recognition || {}
  const foods = Array.isArray(recognition.foods) ? recognition.foods : []
  const riskTags = Array.isArray(evaluation.riskTags) ? evaluation.riskTags : []
  const references = Array.isArray(result?.references) ? result.references : []

  return (
    <div>
      <div className="score-circle">
        <strong>{evaluation.score ?? '--'}</strong>
        <span>分</span>
      </div>

      <p className="summary">{evaluation.summary || '暂无分析摘要'}</p>

      {recognition.demoMode && <span className="badge warn">演示识别模式</span>}

      <h3>识别食物</h3>
      <div className="chips">
        {foods.map((f, i) => (
          <span key={i}>
            {f.name} · {categoryName(f.category)}
          </span>
        ))}
      </div>

      <h3>风险标签</h3>
      <div className="chips risk">
        {riskTags.length ? (
          riskTags.map(x => <span key={x}>{x}</span>)
        ) : (
          <span>暂无明显风险</span>
        )}
      </div>

      <h3>RAG 饮食建议</h3>
      <pre className="advice">{result?.advice || '暂无建议'}</pre>

      <h3>参考知识片段</h3>
      {references.map(ref => (
        <details key={ref.id}>
          <summary>
            {ref.title} · 相似度 {Number(ref.score).toFixed(2)}
          </summary>
          <p>{ref.content}</p>
        </details>
      ))}
    </div>
  )
}

function ReportView({ report, avgLabel, onOpenDetail }) {
  return (
    <div>
      <div className="metrics">
        <div>
          <strong>{report.totalMeals}</strong>
          <span>记录餐次</span>
        </div>

        <div>
          <strong>{avgLabel}</strong>
          <span>平均评分</span>
        </div>
      </div>

      <p>{report.reportText}</p>

      <button className="ghost detail-report-btn" onClick={onOpenDetail} type="button">
        查看详细周报
      </button>

      <h3>风险统计</h3>
      <div className="chips risk">
        {Object.entries(report.riskTotals || {}).length ? (
          Object.entries(report.riskTotals).map(([k, v]) => (
            <span key={k}>
              {k} × {v}
            </span>
          ))
        ) : (
          <span>暂无明显风险</span>
        )}
      </div>
    </div>
  )
}

function WeeklyReportModal({ report, avgLabel, records, onClose }) {
  return (
    <div className="modal-backdrop">
      <div className="weekly-modal">
        <div className="modal-head">
          <div>
            <h2>一周详细饮食报告</h2>
            <p>展示最近 7 天的饮食结构总结和提交图片。</p>
          </div>

          <button className="modal-close" onClick={onClose} type="button">
            关闭
          </button>
        </div>

        <div className="metrics">
          <div>
            <strong>{report?.totalMeals || 0}</strong>
            <span>记录餐次</span>
          </div>

          <div>
            <strong>{avgLabel}</strong>
            <span>平均评分</span>
          </div>
        </div>

        <section className="weekly-section">
          <h3>一周总结</h3>
          <p>{report?.reportText || '最近 7 天暂无记录。'}</p>
        </section>

        <section className="weekly-section">
          <h3>风险统计</h3>
          <div className="chips risk">
            {Object.entries(report?.riskTotals || {}).length ? (
              Object.entries(report.riskTotals).map(([k, v]) => (
                <span key={k}>
                  {k} × {v}
                </span>
              ))
            ) : (
              <span>暂无明显风险</span>
            )}
          </div>
        </section>

        <section className="weekly-section">
          <h3>本周饮食照片</h3>

          {records.length ? (
            <div className="weekly-photo-grid">
              {records.map(record => (
                <article className="weekly-photo-card" key={record.id}>
                  <AuthImage url={record.imageUrl} alt="饭菜图片" />

                  <div className="weekly-photo-info">
                    <strong>{record.score} 分</strong>
                    <span>{new Date(record.createdAt).toLocaleString()}</span>
                  </div>

                  <p>{record.summary}</p>

                  <div className="chips small">
                    {record.foods?.slice(0, 6).map((f, i) => (
                      <span key={i}>{f.name}</span>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <Empty text="最近 7 天暂无饮食照片" />
          )}
        </section>
      </div>
    </div>
  )
}

function buildApiUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//i.test(path)) return path

  const base = import.meta.env.VITE_API_BASE || ''
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

function AuthImage({ url, alt }) {
  const [src, setSrc] = useState('')

  useEffect(() => {
    if (!url) {
      setSrc('')
      return
    }

    let objectUrl = ''
    const token = getToken()

    fetch(buildApiUrl(url), {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })
      .then(res => {
        if (!res.ok) throw new Error('图片加载失败')
        return res.blob()
      })
      .then(blob => {
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      })
      .catch(() => setSrc(''))

    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [url])

  if (!src) {
    return <div className="photo-placeholder">图片加载中</div>
  }

  return <img src={src} alt={alt} />
}

function RecordItem({ record, onDelete }) {
  return (
    <article className="record">
      <div className="record-head">
        <div>
          <strong>{record.score} 分</strong>
          <span>{new Date(record.createdAt).toLocaleString()}</span>
        </div>

        <button
          className="delete-btn"
          onClick={() => onDelete(record.id)}
          type="button"
        >
          删除
        </button>
      </div>

      <p>{record.summary}</p>

      <div className="chips small">
        {record.foods?.slice(0, 6).map((f, i) => (
          <span key={i}>{f.name}</span>
        ))}
      </div>
    </article>
  )
}

function Empty({ text }) {
  return <p className="empty">{text}</p>
}

function categoryName(c) {
  return (
    {
      staple: '主食',
      protein: '蛋白质',
      vegetable: '蔬菜',
      fruit: '水果',
      dairy: '乳制品',
      soup: '汤品',
      drink: '饮品',
      dessert: '甜品',
      fried: '油炸',
      oily: '高油',
      other: '其他'
    }[c] || c
  )
}

createRoot(document.getElementById('root')).render(<App />)
