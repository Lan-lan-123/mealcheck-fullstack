import { test, expect } from '@playwright/test'

test('cookie login refresh logout and CSRF preserve the browser security boundary', async ({ page }) => {
  let loggedIn = false, failLogout = true, csrf = '', logins = 0, logouts = 0
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'legacy-jwt')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'forged-admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (json, status = 200, headers = {}) => route.fulfill({ status, json, headers })
    expect(request.headers().authorization).toBeUndefined()
    if (path === '/api/auth/me') {
      const authenticated = loggedIn && request.headers().cookie?.includes('mealcheck_session=browser-secret')
      return send(authenticated ? { username: 'alice', displayName: 'Alice', role: 'USER' } : { message: 'Not authenticated' }, authenticated ? 200 : 401)
    }
    if (path === '/api/auth/csrf') {
      csrf = `csrf-${Date.now()}`
      return send({ token: csrf, headerName: 'X-XSRF-TOKEN' }, 200,
        { 'Set-Cookie': 'mealcheck_csrf=csrf-cookie; HttpOnly; SameSite=Lax; Path=/' })
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && (request.headers()['x-xsrf-token'] !== csrf || !csrf))
      return send({ code: 'CSRF_INVALID', message: '安全校验失败' }, 403)
    if (path === '/api/auth/captcha') return send({ captchaId: 'fixture', question: '1 + 2 = ?' })
    if (path === '/api/auth/login') {
      logins++; loggedIn = true
      return send({ username: 'alice', displayName: 'Alice', role: 'USER' }, 200,
        { 'Set-Cookie': 'mealcheck_session=browser-secret; HttpOnly; SameSite=Lax; Path=/' })
    }
    if (path === '/api/auth/logout') {
      logouts++
      if (failLogout) return send({ code: 'AUTH_DEPENDENCY_UNAVAILABLE', message: '退出尚未完成，请稍后重试。' }, 503)
      loggedIn = false
      return route.fulfill({ status: 204, headers: { 'Set-Cookie': 'mealcheck_session=; Max-Age=0; HttpOnly; SameSite=Lax; Path=/' } })
    }
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(path.endsWith('/goals/current') ? { goalType: 'balanced' } : {})
  })
  await page.goto('/')
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem('mealcheck_token'))).toBeNull()
  await page.getByRole('textbox').nth(0).fill('alice')
  await page.locator('input[type=password]').fill('password')
  await page.getByPlaceholder('答案').fill('3')
  await page.getByRole('button', { name: '用户登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  expect(logins).toBe(1)
  expect(await page.evaluate(() => document.cookie)).not.toContain('browser-secret')
  expect((await page.context().cookies()).find(cookie => cookie.name === 'mealcheck_session')?.httpOnly).toBe(true)
  expect(await page.evaluate(() => localStorage.getItem('mealcheck_user'))).toBeNull()
  await page.reload()
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('退出尚未完成')
  await page.getByRole('button', { name: '知道了', exact: true }).click()
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  expect(logouts).toBe(1)
  failLogout = false
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toBeVisible()
  expect((await page.context().cookies()).find(cookie => cookie.name === 'mealcheck_session')).toBeUndefined()
})

test('a temporary authentication outage shows retry and never trusts a stored role', async ({ page }) => {
  let unavailable = true
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/auth/me') return route.fulfill({ status: unavailable ? 503 : 401, json: { message: '认证服务暂时不可用' } })
    return route.fulfill({ json: { captchaId: 'fixture', question: '1 + 2 = ?' } })
  })
  await page.goto('/')
  await expect(page.getByRole('button', { name: '重试登录状态' })).toBeVisible()
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toHaveCount(0)
  unavailable = false
  await page.getByRole('button', { name: '重试登录状态' }).click()
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toBeVisible()
})
