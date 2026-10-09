import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'

const NavigationContext = createContext(null)

export function WorkspaceNavigationProvider({ children }) {
  const [pathname, setPathname] = useState(() => window.location.pathname)
  useEffect(() => {
    const update = () => setPathname(window.location.pathname)
    window.addEventListener('popstate', update)
    return () => window.removeEventListener('popstate', update)
  }, [])
  const navigate = useCallback((path, { replace = false } = {}) => {
    if (path === window.location.pathname && !window.location.search && !window.location.hash) return
    window.history[replace ? 'replaceState' : 'pushState'](null, '', path)
    setPathname(path)
  }, [])
  const value = useMemo(() => ({ pathname, navigate }), [pathname, navigate])
  return <NavigationContext.Provider value={value}>{children}</NavigationContext.Provider>
}

export function useWorkspaceNavigation() { return useContext(NavigationContext) }

export function useWorkspaceSection(mode) {
  const { pathname, navigate } = useWorkspaceNavigation()
  const section = pathname.startsWith(`/${mode}/`) ? pathname.split('/')[2] : 'overview'
  return [section, useCallback(key => navigate(`/${mode}/${key}`), [mode, navigate])]
}
