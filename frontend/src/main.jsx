import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Utensils } from 'lucide-react'
import { captcha, clearSession, deleteCurrentAccount, getUser, login, logout, register, setSession } from './api/client'
import AdminPage from './pages/AdminPage'
import UserWorkspacePage from './pages/UserWorkspacePage'
import { DialogProvider, useDialog } from './components/DialogProvider'
import { WorkspaceNavigationProvider, useWorkspaceNavigation } from './hooks/useWorkspaceNavigation'
import { resolveWorkspaceRoute } from './utils/workspaceRoute'
import './styles/index.css'

function isAdmin(user) {
  return user?.role === 'ADMIN'
}

function AuthPage({ onAuthed }) {
  const [mode, setMode] = useState('login')
  const [loginRole, setLoginRole] = useState('USER')
  const [form, setForm] = useState({
    username: '',
    password: '',
    displayName: '',
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
      username: '',
      password: '',
      displayName: '',
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
            displayName: form.displayName,
            captchaId: captchaData?.captchaId,
            captchaAnswer: form.captchaAnswer
          })
      setSession(auth)
      onAuthed(auth)
    } catch (err) {
      setError(err.message)
      refreshCaptcha().catch(() => {})
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
            <input type="password" minLength={mode === 'register' ? 10 : undefined} value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} />
          </label>
          {mode === 'register' && (
            <label>
              昵称
              <input value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} />
            </label>
          )}
          {(mode === 'login' || mode === 'register') && (
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
  const { pathname, navigate } = useWorkspaceNavigation()
  const { confirm, prompt, notify } = useDialog()
  const route = resolveWorkspaceRoute(pathname, user?.role)
  useEffect(() => {
    if (user && pathname !== route.path) navigate(route.path, { replace: true })
  }, [user, pathname, route.path, navigate])
  useEffect(() => {
    const expired = () => { setUser(null); navigate('/', { replace: true }) }
    window.addEventListener('mealcheck:session-expired', expired)
    return () => window.removeEventListener('mealcheck:session-expired', expired)
  }, [navigate])

  function handleAuthed(auth) {
    const nextUser = {
      username: auth.username,
      displayName: auth.displayName,
      role: auth.role || 'USER'
    }
    setUser(nextUser)
  }

  async function handleLogout() {
    try {
      await logout()
      clearSession()
      setUser(null)
      navigate('/', { replace: true })
    } catch (err) {
      notify(err.message || '退出失败，请稍后重试')
    }
  }

  async function handleDeleteAccount() {
    if (!await confirm('饮食记录、图片和对话将被永久删除，且无法恢复。', { title: '注销账号', danger: true, confirmText: '继续注销' })) return
    const password = await prompt('请输入当前密码确认注销账号。', { title: '确认注销账号', inputLabel: '当前密码', inputType: 'password', danger: true, confirmText: '确认注销' })
    if (!password) return
    try {
      await deleteCurrentAccount(password)
      clearSession()
      setUser(null)
      navigate('/', { replace: true })
    } catch (err) {
      notify(err.message || '注销账号失败，请稍后重试')
    }
  }

  if (!user) {
    return <AuthPage onAuthed={handleAuthed} />
  }

  if (route.mode === 'admin' && isAdmin(user)) {
    return <AdminPage onBack={() => navigate('/user/overview')} onLogout={handleLogout} />
  }

  return (
    <UserWorkspacePage
      user={user}
      onLogout={handleLogout}
      onDeleteAccount={handleDeleteAccount}
      onOpenAdmin={() => {
        if (isAdmin(user)) navigate('/admin/overview')
      }}
    />
  )
}

createRoot(document.getElementById('root')).render(<WorkspaceNavigationProvider><DialogProvider><App /></DialogProvider></WorkspaceNavigationProvider>)
