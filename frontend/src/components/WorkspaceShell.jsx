import { useEffect, useRef, useState } from 'react'
import { ArrowUpRight, ChevronDown, ChevronLeft, ChevronRight, Leaf, LogOut, Menu, X } from 'lucide-react'

const mobileQuery = '(max-width: 760px)'

export default function WorkspaceShell({ mode = 'user', user, items, activeKey, onNavigate, onLogout, switchAction, children, actions }) {
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('mealcheck:sidebar-collapsed') === 'true' } catch { return false }
  })
  const [mobile, setMobile] = useState(() => window.matchMedia(mobileQuery).matches)
  const [open, setOpen] = useState(false)
  const [closedGroups, setClosedGroups] = useState(() => mode === 'admin' ? { '质量评测': true, '系统管理': true } : {})
  const sidebar = useRef(null)
  const main = useRef(null)
  const active = items.find(item => item.key === activeKey) || items[0]
  const groups = [...new Set(items.map(item => item.group))]
  const admin = mode === 'admin'
  const name = user?.displayName || user?.username || (admin ? '管理员' : '用户')

  useEffect(() => {
    setClosedGroups(current => current[active.group] ? { ...current, [active.group]: false } : current)
  }, [activeKey, active.group])

  useEffect(() => {
    const media = window.matchMedia(mobileQuery)
    const change = event => { setMobile(event.matches); setOpen(false) }
    media.addEventListener('change', change)
    return () => media.removeEventListener('change', change)
  }, [])

  useEffect(() => {
    if (!mobile || !open) return
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => [...sidebar.current.querySelectorAll('button:not(:disabled)')].filter(element => element.getClientRects().length)
    focusable()[0]?.focus()
    const keydown = event => {
      if (event.key === 'Escape') { event.preventDefault(); setOpen(false) }
      if (event.key === 'Tab') {
        const elements = focusable(), first = elements[0], last = elements.at(-1)
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
      }
    }
    const element = sidebar.current
    element.addEventListener('keydown', keydown)
    return () => {
      document.body.style.overflow = overflow
      element.removeEventListener('keydown', keydown)
      if (previous?.isConnected) previous.focus()
    }
  }, [mobile, open])

  function toggleCollapsed() {
    const next = !collapsed
    setCollapsed(next)
    try { localStorage.setItem('mealcheck:sidebar-collapsed', String(next)) } catch { /* Layout works without storage. */ }
  }

  function navigate(key) {
    onNavigate(key)
    setOpen(false)
    window.scrollTo({ top: 0, behavior: 'instant' })
    if (!mobile) main.current?.focus({ preventScroll: true })
  }

  return (
    <div className={`workspace workspace--${mode}${collapsed && !mobile ? ' is-collapsed' : ''}`}>
      <a className="skip-link" href="#workspace-main">跳转到主要内容</a>
      {mobile && open && <div className="sidebar-backdrop" aria-hidden="true" onClick={() => setOpen(false)} />}
      <aside ref={sidebar} id="workspace-sidebar" className={`workspace-sidebar${open ? ' is-open' : ''}`}
        role={mobile && open ? 'dialog' : undefined} aria-modal={mobile && open ? true : undefined}
        aria-label={admin ? '管理员工作栏' : '用户工作栏'} inert={mobile && !open}>
        <div className="workspace-brand">
          <span className="workspace-logo"><Leaf size={24} strokeWidth={1.8} /></span>
          <div className="sidebar-label"><strong>MealCheck<span>.</span></strong><small>{admin ? '管理工作台' : '让每一餐更有营养'}</small></div>
          {mobile && <button className="icon-button sidebar-close" type="button" aria-label="关闭导航菜单" onClick={() => setOpen(false)}><X size={20} /></button>}
        </div>
        <nav className="workspace-nav" aria-label={admin ? '管理页面切换' : '用户页面切换'}>
          {groups.map((group, groupIndex) => <div className="workspace-nav-group" key={group}>
            <div className="nav-group">
              {collapsed && !mobile ? null : <button type="button" aria-expanded={!closedGroups[group]} aria-controls={`nav-group-${groupIndex}`}
                onClick={() => setClosedGroups(current => ({ ...current, [group]: !current[group] }))}>
                <span>{group}</span><ChevronDown size={13} className={closedGroups[group] ? 'group-is-closed' : ''} />
              </button>}
            </div>
            <div id={`nav-group-${groupIndex}`} className="section-panel" hidden={closedGroups[group] && !(collapsed && !mobile)}>
          {items.filter(item => item.group === group).map(item => {
            const Icon = item.icon
            return <div key={item.key}>
              <button type="button" className={`workspace-nav-item${item.key === activeKey ? ' active' : ''}`}
                aria-current={item.key === activeKey ? 'page' : undefined} aria-label={item.label}
                title={collapsed && !mobile ? item.label : undefined} onClick={() => navigate(item.key)}>
                <Icon size={20} strokeWidth={1.7} /><span className="sidebar-label">{item.label}</span>
                {item.key === activeKey && <span className="nav-active-dot" aria-hidden="true" />}
              </button>
            </div>
          })}
            </div>
          </div>)}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-note sidebar-label"><Leaf size={19} /><strong>{admin ? '有序管理，安心运行' : '好好吃饭，从这一餐开始'}</strong><p>{admin ? '用户、内容与运行状态，一处掌握。' : '记录每一餐，发现饮食习惯里的小进步。'}</p></div>
          {switchAction && <button className="workspace-nav-item" type="button" aria-label={switchAction.label} title={switchAction.label} onClick={switchAction.onClick}><ArrowUpRight size={20} /><span className="sidebar-label">{switchAction.label}</span></button>}
          <div className="sidebar-account">
            <span className="workspace-avatar" aria-hidden="true">{name.slice(0, 1).toUpperCase()}</span>
            <div className="sidebar-label"><strong title={name}>{name}</strong><small>{admin ? '管理员' : '个人空间'}</small></div>
            {onLogout && <button className="icon-button" type="button" onClick={onLogout} aria-label={admin ? '退出登录' : '退出'} title="退出登录"><LogOut size={18} /></button>}
          </div>
          {!mobile && <button className="sidebar-collapse" type="button" onClick={toggleCollapsed} aria-label={collapsed ? '展开侧栏' : '折叠侧栏'} aria-expanded={!collapsed} aria-controls="workspace-sidebar" title={collapsed ? '展开侧栏' : '折叠侧栏'}>
            {collapsed ? <ChevronRight size={18} /> : <><ChevronLeft size={18} /><span>折叠侧栏</span></>}
          </button>}
        </div>
      </aside>
      <div className="workspace-frame" inert={mobile && open}>
        <header className="workspace-topbar">
          <div className="workspace-breadcrumb">
            {mobile && <button className="icon-button" type="button" aria-label="打开导航菜单" aria-expanded={open} aria-controls="workspace-sidebar" onClick={() => setOpen(true)}><Menu size={22} /></button>}
            <span>{admin ? '管理工作台' : '我的饮食空间'}</span><ChevronRight size={14} /><strong>{active.label}</strong>
          </div>
          <div className="workspace-topbar-actions">{actions}<span className="workspace-role">{admin ? '管理员' : '健康饮食 · 每一天'}</span></div>
        </header>
        <main ref={main} id="workspace-main" className="workspace-content" tabIndex={-1}>
          <div className="workspace-page-heading"><div><span className="workspace-eyebrow">{admin ? 'MEALCHECK / WORKSPACE' : 'MEALCHECK / YOUR DAILY BALANCE'}</span><h1>{active.label}</h1><p>{active.description}</p></div><span className="page-heading-icon" aria-hidden="true"><active.icon size={30} strokeWidth={1.4} /></span></div>
          {children}
          <footer className="workspace-footer">MealCheck<span>每一餐，都是更好的开始</span></footer>
        </main>
      </div>
    </div>
  )
}
