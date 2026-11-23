import { test, expect } from './fixtures/authTest.js'

test('administrator can cancel, ban and unban a user while administrator accounts stay protected', async ({ page }, testInfo) => {
  const errors = [], mutations = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', displayName: '管理员', role: 'ADMIN' }))
  })
  const users = [
    { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', enabled: true, createdAt: '2026-10-24T10:00:00', lastUploadAt: null },
    { id: 2, username: 'alice', displayName: '小明', role: 'USER', enabled: true, createdAt: '2026-10-24T11:00:00', lastUploadAt: null },
  ]
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/admin/users/2/status') {
      expect(request.method()).toBe('PUT')
      expect(request.headers()['x-xsrf-token']).toBe('fixture-csrf')
      expect(request.headers().authorization).toBeUndefined()
      const payload = request.postDataJSON()
      mutations.push(payload)
      users[1].enabled = payload.enabled
      return send(users[1])
    }
    if (path === '/api/admin/users') return send({ content: users, totalElements: 2, totalPages: 1, page: 0, size: 20 })
    return send({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 })
  })
  await page.goto('/admin/users')
  const admin = page.getByRole('row').filter({ has: page.getByRole('cell', { name: 'admin', exact: true }) })
  const alice = page.getByRole('row').filter({ has: page.getByRole('cell', { name: 'alice', exact: true }) })
  await expect(alice.getByRole('cell', { name: '正常', exact: true })).toBeVisible()
  await expect(admin.getByRole('button', { name: '封禁', exact: true })).toHaveCount(0)
  await alice.getByRole('button', { name: '封禁', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  expect(mutations).toEqual([])
  await alice.getByRole('button', { name: '封禁', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('现有登录凭证也会失效')
  await page.getByRole('dialog').getByRole('button', { name: '确认封禁', exact: true }).click()
  await expect(alice.getByRole('cell', { name: '已封禁', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('account-banned.png'), fullPage: true })
  await alice.getByRole('button', { name: '解封', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('解封后需重新登录')
  await page.getByRole('dialog').getByRole('button', { name: '确认解封', exact: true }).click()
  await expect(alice.getByRole('cell', { name: '正常', exact: true })).toBeVisible()
  expect(mutations).toEqual([{ enabled: false }, { enabled: true }])
  expect(errors).toEqual([])
})
