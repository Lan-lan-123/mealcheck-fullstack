const API_BASE = import.meta.env.VITE_API_BASE || ''

export function getToken() {
  return localStorage.getItem('mealcheck_token')
}

export function setSession(auth) {
  localStorage.setItem('mealcheck_token', auth.token)
  localStorage.setItem(
    'mealcheck_user',
    JSON.stringify({
      username: auth.username,
      displayName: auth.displayName,
      role: auth.role || 'USER'
    })
  )
}

export function clearSession() {
  localStorage.removeItem('mealcheck_token')
  localStorage.removeItem('mealcheck_user')
}

export function getUser() {
  const raw = localStorage.getItem('mealcheck_user')
  return raw ? JSON.parse(raw) : null
}

function authHeaders(extraHeaders = {}) {
  const headers = { ...extraHeaders }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

async function request(path, options = {}) {
  const headers = authHeaders(options.headers || {})

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  })

  const text = await res.text()
  let data = null

  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }

  if (!res.ok) {
    if (typeof data === 'string') {
      throw new Error(data || '请求失败')
    }
    throw new Error(data?.message || data?.error || '请求失败')
  }

  return data
}

export function register(payload) {
  return request('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function login(payload) {
  return request('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function analyzeMeal(file, goal) {
  const form = new FormData()
  form.append('image', file)
  form.append('goal', goal)
  return request('/api/meals/analyze', {
    method: 'POST',
    body: form
  })
}

export function listMeals() {
  return request('/api/meals')
}

export function weeklyReport(days = 7) {
  return request(`/api/reports/weekly?days=${days}`)
}

export function deleteMeal(id) {
  return request(`/api/meals/${id}`, {
    method: 'DELETE'
  })
}

export function reindexKnowledge() {
  return request('/api/knowledge/reindex', {
    method: 'POST'
  })
}

/**
 * =========================
 * 管理员后台接口
 * =========================
 */

export function adminStats() {
  return request('/api/admin/stats')
}

export function adminDashboard() {
  return request('/api/admin/dashboard')
}

export function adminUsers() {
  return request('/api/admin/users')
}

export function adminDeleteUser(id) {
  return request(`/api/admin/users/${id}`, {
    method: 'DELETE'
  })
}

function toQuery(params = {}) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, value)
    }
  })
  const text = query.toString()
  return text ? `?${text}` : ''
}

export function adminMeals(params = {}) {
  return request(`/api/admin/meals${toQuery(params)}`)
}

export function adminDeleteMeal(id) {
  return request(`/api/admin/meals/${id}`, {
    method: 'DELETE'
  })
}

export function adminKnowledgeChunks(params = {}) {
  return request(`/api/admin/knowledge/chunks${toQuery(params)}`)
}

export function adminAddKnowledgeChunk(payload) {
  return request('/api/admin/knowledge/chunks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function adminDeleteKnowledgeChunk(id) {
  return request(`/api/admin/knowledge/chunks/${id}`, {
    method: 'DELETE'
  })
}

export function adminUpdateKnowledgeChunk(id, payload) {
  return request(`/api/admin/knowledge/chunks/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function adminReindexKnowledge() {
  return request('/api/admin/knowledge/reindex', {
    method: 'POST'
  })
}
