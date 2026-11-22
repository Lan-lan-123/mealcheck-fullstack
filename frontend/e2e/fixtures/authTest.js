import { test as base, expect } from '@playwright/test'

// Deterministic page tests model /auth/me and CSRF explicitly. This metadata is
// test-only, never a production login source. Security lifecycle tests use the base fixture.
export const test = base.extend({
  page: async ({ page }, use) => {
    let sessionUser = null
    await page.exposeBinding('__mealcheckFixtureUser', (_source, user) => { sessionUser = user })
    await page.addInitScript(() => {
      const original = Storage.prototype.setItem
      Storage.prototype.setItem = function (key, value) {
        if (key === 'mealcheck_fixture_user') window.__mealcheckFixtureUser(JSON.parse(value))
        return original.call(this, key, value)
      }
      const persisted = sessionStorage.getItem('mealcheck_fixture_user')
      if (persisted) window.__mealcheckFixtureUser(JSON.parse(persisted))
    })
    const route = page.route.bind(page)
    page.route = (url, handler, options) => route(url, async (intercepted, request) => {
      const path = new URL(request.url()).pathname
      if (path === '/api/auth/csrf') return intercepted.fulfill({ json: { token: 'fixture-csrf', headerName: 'X-XSRF-TOKEN' } })
      if (path === '/api/auth/me') {
        const user = sessionUser
        return intercepted.fulfill({ status: user ? 200 : 401, json: user || { message: 'Not authenticated' } })
      }
      if (path.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())
          && request.headers()['x-xsrf-token'] !== 'fixture-csrf')
        return intercepted.fulfill({ status: 403, json: { code: 'CSRF_INVALID', message: 'Missing CSRF token' } })
      if (['/api/auth/login', '/api/auth/register', '/api/auth/logout'].includes(path)) {
        const fulfill = intercepted.fulfill.bind(intercepted)
        intercepted.fulfill = async response => {
          if (!response.status || response.status < 300) {
            if (path.endsWith('/logout')) { sessionUser = null; await page.evaluate(() => sessionStorage.removeItem('mealcheck_fixture_user')) }
            else {
              const data = response.json || (typeof response.body === 'string' ? JSON.parse(response.body) : null)
              if (data?.username) sessionUser = { username: data.username, displayName: data.displayName, role: data.role || 'USER' }
              if (data?.username) await page.evaluate(user => sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify(user)),
                { username: data.username, displayName: data.displayName, role: data.role || 'USER' })
            }
          }
          return fulfill(response)
        }
      }
      return handler(intercepted, request)
    }, options)
    await use(page)
  },
})
export { expect }
