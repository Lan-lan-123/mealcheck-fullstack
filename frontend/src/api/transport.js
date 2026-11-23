// @ts-check
import { ResponseValidationError, validateApiResponse } from './validation.js'

/** @typedef {{ token: string; headerName: string }} CsrfToken */

export const API_BASE = import.meta.env?.VITE_API_BASE || ''
const REQUEST_TIMEOUT_MS = 75_000
export const LONG_REQUEST_TIMEOUT_MS = 180_000
const LONG_REQUEST_PATHS = new Set([
  '/api/assistant/ask', '/api/meals/analyze', '/api/reports/weekly',
  '/api/admin/vision-evaluation/recognize'
])
export class ApiError extends Error {
  /** @param {string | undefined} message @param {number} status @param {string} [code] @param {number} [retryAfter] @param {string | null} [requestId] */
  constructor(message, status, code, retryAfter, requestId) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryAfter = retryAfter
    this.requestId = requestId
  }
}

/** @type {import('./contracts').SessionUser | null} */
let sessionUser = null
let sessionVersion = 0
/** @type {CsrfToken | null} */
let csrfValue = null
/** @type {Promise<CsrfToken> | null} */
let csrfPending = null
let csrfVersion = 0
export function resetCsrf() { csrfVersion++; csrfValue = null; csrfPending = null }
function removeLegacyCredentials() {
  try { localStorage.removeItem('mealcheck_token'); localStorage.removeItem('mealcheck_user') } catch { /* Storage may be disabled. */ }
}
removeLegacyCredentials()
/** @param {number} version @param {boolean} authenticated */
export function expireSession(version, authenticated) {
  if (!authenticated || version !== sessionVersion) return
  clearSession()
  window.dispatchEvent(new Event('mealcheck:session-expired'))
}
/** @param {Record<string, string>} headers @param {AbortSignal} signal @returns {Promise<Record<string, string>>} */
export async function csrfHeaders(headers, signal) {
  signal.throwIfAborted()
  if (!csrfValue) {
    if (!csrfPending) {
      const version = csrfVersion
      csrfPending = fetch(`${API_BASE}/api/auth/csrf`, { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) })
        .then(async response => {
          if (!response.ok) throw new ApiError('安全校验暂时不可用，请稍后重试。', response.status)
          const value = await response.json()
          if (!value || typeof value.token !== 'string' || !value.token || value.headerName !== 'X-XSRF-TOKEN') throw new ResponseValidationError('auth.csrf')
          const token = { token: value.token, headerName: value.headerName }
          if (version === csrfVersion) csrfValue = token
          return token
        }).finally(() => { if (version === csrfVersion) csrfPending = null })
    }
    const pending = csrfPending
    const value = await new Promise((resolve, reject) => {
      const abort = () => reject(signal.reason || new DOMException('Aborted', 'AbortError'))
      if (signal.aborted) { abort(); return }
      signal.addEventListener('abort', abort, { once: true })
      pending.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
    })
    if (signal.aborted) throw signal.reason
    return { ...headers, [value.headerName]: value.token }
  }
  return { ...headers, [csrfValue.headerName]: csrfValue.token }
}

export function sessionSnapshot() { return { version: sessionVersion, authenticated: !!sessionUser } }
/** @param {string} method @param {unknown} body @returns {import('./contracts').RequestOptions} */
export const jsonOptions = (method, body) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })

/** @param {import("./contracts").AuthSession} auth @returns {void} */
export function setSession(auth) {
  sessionVersion++; resetCsrf(); removeLegacyCredentials()
  sessionUser = { username: auth.username, displayName: auth.displayName, role: auth.role || 'USER' }
}

export function clearSession() {
  sessionVersion++; sessionUser = null; resetCsrf(); removeLegacyCredentials()
}

/**  @returns {import("./contracts").SessionUser | null} */
export function getUser() {
  return sessionUser
}
/** @param {AbortSignal} [signal] @returns {Promise<import('./contracts').SessionUser | null>} */
export async function restoreSession(signal) {
  const version = sessionVersion
  try {
    const user = await request('/api/auth/me', { signal })
    if (!user || typeof user.username !== 'string' || !['USER', 'ADMIN'].includes(user.role)) throw new ResponseValidationError('auth.me')
    if (version === sessionVersion) setSession(user)
  } catch (error) {
    if (error.status !== 401) throw error
    if (version === sessionVersion) clearSession()
  }
  return getUser()
}

/** @param {string} path @param {import("./contracts").RequestOptions} [options] @returns {Promise<any>} */
export async function request(path, options = {}) {
  const version = sessionVersion, authenticated = !!sessionUser
  const controller = new AbortController()
  const abort = () => controller.abort(options.signal?.reason)
  if (options.signal?.aborted) abort()
  else options.signal?.addEventListener('abort', abort, { once: true })
  let timedOut = false
  const { timeoutMs, ...fetchOptions } = options
  const requestTimeout = timeoutMs ?? (LONG_REQUEST_PATHS.has(path.split('?')[0]) ? LONG_REQUEST_TIMEOUT_MS : REQUEST_TIMEOUT_MS)
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, requestTimeout)
  try {
    const method = (options.method || 'GET').toUpperCase()
    const headers = ['GET', 'HEAD', 'OPTIONS'].includes(method) ? { ...options.headers } : await csrfHeaders(options.headers || {}, controller.signal)
    controller.signal.throwIfAborted()
    const res = await fetch(`${API_BASE}${path}`, { ...fetchOptions, credentials: 'include', headers, signal: controller.signal })

    const text = await res.text()
    let data = null

    try {
      data = text ? JSON.parse(text) : null
    } catch {
      data = text
    }

    if (!res.ok) {
      if (res.status === 401 && !['/api/auth/login', '/api/auth/register'].includes(path)) expireSession(version, authenticated)
      if (res.status === 403 && data?.code === 'CSRF_INVALID') resetCsrf()
      const retryAfter = Number(res.headers.get('Retry-After')) || 0
      const fallback = res.status === 401 ? '登录已失效，请重新登录。' : res.status === 429 ? '请求过于频繁，请稍后重试。' : '请求失败'
      throw new ApiError((typeof data === 'string' ? data : data?.message || data?.error) || fallback,
        res.status, data?.code, retryAfter, res.headers.get('X-Request-ID'))
    }

    return validateApiResponse(path, data, options.method)
  } catch (error) {
    if (timedOut) throw new ApiError('请求超时，请先查询已有结果或任务状态，确认后再重试。', 408, 'CLIENT_TIMEOUT', 0, null)
    throw error
  } finally {
    clearTimeout(timeout)
    options.signal?.removeEventListener('abort', abort)
  }
}

