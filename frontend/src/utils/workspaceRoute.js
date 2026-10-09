export const workspaceSections = {
  user: ['overview', 'records', 'trends', 'reports', 'goals'],
  admin: ['overview', 'users', 'meals', 'nonfood', 'knowledge', 'assistant-stats', 'rag-evaluation', 'assistant-evaluation', 'meal-corrections', 'monitor', 'agent-coordination', 'task-failures', 'audit']
}

export function resolveWorkspaceRoute(pathname, role) {
  const [, mode, section] = pathname.split('/')
  const valid = workspaceSections[mode]?.includes(section) && pathname === `/${mode}/${section}`
  if (valid && (mode !== 'admin' || role === 'ADMIN')) return { mode, section, path: pathname }
  const defaultMode = role === 'ADMIN' ? 'admin' : 'user'
  return { mode: defaultMode, section: 'overview', path: `/${defaultMode}/overview` }
}
