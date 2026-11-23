import { test, expect } from './fixtures/authTest.js'

async function session(page, role = 'USER') {
  await page.addInitScript(role => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'tester', role }))
    const original = window.fetch.bind(window)
    window.optimizationRequests = []
    window.fetch = (url, options = {}) => {
      const entry = { path: new URL(url, location.href).pathname, aborted: false }
      window.optimizationRequests.push(entry)
      options.signal?.addEventListener('abort', () => { entry.aborted = true }, { once: true })
      return original(url, options)
    }
  }, role)
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/admin/dashboard') return send({ overview: {} })
    if (path === '/api/health') return send({ status: 'UP' })
    if (path === '/api/meals/trends') return send({ total: 0, averageScore: 0 })
    if (path === '/api/meals/summaries') return send({ content: [{ id: 1, createdAt: '2026-10-14T04:00:00', score: 88, summary: '摘要餐食', foodNames: ['米饭', '青菜'] }], page: 0, totalPages: 1, totalElements: 1 })
    if (path === '/api/meals/1') return send({ id: 1, createdAt: '2026-10-14T04:00:00', score: 88, summary: '摘要餐食', foods: [{ name: '米饭', category: 'staple' }], note: '完整备注', metadataVersion: 3, advice: '完整餐食建议', revision: 2 })
    if (path === '/api/assistant/conversations') return send([])
    return send(null)
  })
}

for (const role of ['USER', 'ADMIN']) {
  test(`${role} cancels a pending page read on navigation`, async ({ page }) => {
    await session(page, role)
    const path = role === 'USER' ? '/api/meals/summaries' : '/api/admin/users'
    let release
    const waiting = new Promise(resolve => { release = resolve })
    await page.route(`**${path}*`, async route => {
      await waiting
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ content: [], totalElements: 0, totalPages: 0 }) }).catch(() => {})
    })
    try {
      await page.goto(role === 'USER' ? '/user/records' : '/admin/users')
      await expect.poll(() => page.evaluate(path => window.optimizationRequests.some(request => request.path === path), path)).toBe(true)
      await page.getByRole('button', { name: role === 'USER' ? '饮食趋势' : '系统概览', exact: true }).click()
      await expect.poll(() => page.evaluate(path => window.optimizationRequests.find(request => request.path === path)?.aborted, path)).toBe(true)
    } finally { release() }
  })
}

test('summary records fetch complete data only for detail and metadata editing', async ({ page }) => {
  await session(page)
  await page.goto('/user/records')
  await expect(page.locator('.record-list')).toContainText('青菜')
  expect(await page.evaluate(() => window.optimizationRequests.filter(request => request.path === '/api/meals/1').length)).toBe(0)
  await page.getByRole('button', { name: '查看餐食详情', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '餐食记录详情', exact: true })
  await expect(drawer).toContainText('完整餐食建议')
  await expect(drawer).toContainText('完整备注')
  await page.getByRole('button', { name: '关闭详情' }).click()
  await page.getByRole('button', { name: '修改用餐信息', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '修改用餐信息' }).getByLabel('备注')).toHaveValue('完整备注')
  expect(await page.evaluate(() => window.optimizationRequests.filter(request => request.path === '/api/meals/1').length)).toBe(2)
})

test('mobile journal buttons share theme, focus and disabled styling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await session(page)
  await page.goto('/user/records')
  await page.getByRole('button', { name: '手动记录 / 补记' }).click()
  const dialog = page.getByRole('dialog', { name: '手动记录与补记' })
  const add = dialog.getByRole('button', { name: '添加手动食物' })
  expect((await dialog.getByRole('button', { name: '关闭', exact: true }).boundingBox()).width).toBeLessThan(90)
  await expect(page.locator('body')).toHaveCSS('overflow', 'hidden')
  await expect(add).toHaveCSS('border-radius', '9px')
  await expect(add).toHaveCSS('background-color', 'rgb(237, 245, 239)')
  await expect(add).toHaveCSS('color', 'rgb(70, 118, 90)')
  expect((await add.boundingBox()).height).toBeGreaterThanOrEqual(44)
  await expect(dialog.getByRole('button', { name: '移除' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '移除' })).toHaveCSS('cursor', 'not-allowed')
  await page.keyboard.press('Tab')
  await add.focus()
  await expect(add).toHaveCSS('outline-style', 'solid')
  await page.screenshot({ path: 'test-results/optimization-mobile-buttons.png', fullPage: true })
  await page.keyboard.press('Escape')
  await expect(page.locator('body')).not.toHaveCSS('overflow', 'hidden')
})

test('failed lazy page loading offers a reload action that recovers', async ({ page }) => {
  await session(page)
  await page.route('**/src/pages/UserWorkspacePage.jsx*', route => route.abort())
  await page.goto('/user/overview')
  await expect(page.getByRole('heading', { name: '页面暂时无法显示' })).toBeVisible()
  await page.unroute('**/src/pages/UserWorkspacePage.jsx*')
  await page.getByRole('button', { name: '重新加载页面' }).click()
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
})
