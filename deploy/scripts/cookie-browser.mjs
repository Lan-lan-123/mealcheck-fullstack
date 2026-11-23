// Private CLI/browser verification transport; never included in the frontend bundle.
const csrf = new Map()
export async function cookieFetch(input, options = {}) {
  const url = new URL(input), headers = new Headers(options.headers)
  if (url.pathname.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
    if (!csrf.has(url.origin)) {
      const response = await globalThis.fetch(url.origin + '/api/auth/csrf', { signal: AbortSignal.timeout(15000) })
      if (!response.ok) throw new Error('CSRF bootstrap failed')
      const value = await response.json()
      const cookie = response.headers.getSetCookie().map(item => item.split(';')[0]).find(item => /^(?:__Host-)?mealcheck_csrf=/.test(item))
      if (!cookie || value.headerName !== 'X-XSRF-TOKEN' || !value.token) throw new Error('Invalid CSRF bootstrap')
      csrf.set(url.origin, { cookie, token: value.token })
    }
    const value = csrf.get(url.origin)
    headers.set('Cookie', [headers.get('Cookie'), value.cookie].filter(Boolean).join('; '))
    headers.set('X-XSRF-TOKEN', value.token)
  }
  const response = await globalThis.fetch(input, { ...options, headers })
  if (response.ok && ['/api/auth/login', '/api/auth/register'].includes(url.pathname)) {
    const cookie = response.headers.getSetCookie().map(item => item.split(';')[0]).find(item => /^(?:__Host-)?mealcheck_session=/.test(item))
    if (cookie) {
      const value = await response.json()
      return new Response(JSON.stringify({ ...value, token: cookie.slice(cookie.indexOf('=') + 1) }), { status: response.status, headers: response.headers })
    }
  }
  return response
}
export async function installBrowserSession(context, base, token) {
  const url = new URL(base)
  const probe = await context.request.get(url.origin + '/api/auth/csrf')
  if (!probe.ok()) throw new Error('Cannot determine deployed authentication cookie settings')
  const secure = probe.headersArray().some(header => header.name.toLowerCase() === 'set-cookie' && header.value.startsWith('__Host-mealcheck_csrf='))
    || (await context.cookies(base)).some(cookie => cookie.name === '__Host-mealcheck_csrf')
  await context.addCookies([{ name: secure ? '__Host-mealcheck_session' : 'mealcheck_session',
    value: token, domain: url.hostname, path: '/', httpOnly: true, secure, sameSite: 'Lax' }])
}
