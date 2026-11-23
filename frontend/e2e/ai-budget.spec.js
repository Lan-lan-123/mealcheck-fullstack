import { test, expect } from './fixtures/authTest.js'

test('administrator can render successful and failed AI calls with timestamps', async ({ page }) => {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const data = path === '/api/health' ? { status: 'UP', aiCalls: [
      { operation: 'vision-recognition', success: false, message: '最近调用失败', at: '2026-10-18T08:32:59Z' },
      { operation: 'meal-advice', success: true, message: '最近调用成功', at: '2026-10-18T08:30:00Z' },
    ] } : path === '/api/admin/dashboard' ? { system: { aiMetrics: [
      { operation: 'vision-recognition', calls: 1, successCount: 0, failureCount: 1, averageLatencyMs: 335, maxLatencyMs: 335 },
    ] } } : {}
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
  })
  await page.goto('/admin/monitor')
  await page.getByRole('button', { name: 'AI 调用', exact: true }).click()
  const failed = page.locator('.meal-chart-card').filter({ has: page.getByRole('heading', { name: 'vision-recognition', exact: true }) })
  await expect(failed).toContainText('最近调用失败')
  await expect(failed).toContainText('16:32:59')
  const successful = page.locator('.meal-chart-card').filter({ has: page.getByRole('heading', { name: 'meal-advice', exact: true }) })
  await expect(successful).toContainText('最近调用成功')
  await expect(successful).toContainText('16:30:00')
  await expect(page.locator('.ai-metrics-table')).toContainText('335 ms')
  expect(errors).toEqual([])
})

test('administrator sees the global quota, warning and refreshed consumption', async ({ page }) => {
  let attempts = 85000
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (url.pathname === '/api/admin/ai-budget') return send({ enabled: true, monitorHealthy: true,
      unsettled: 0, stale: 0, oldest_seconds: 0, rejected: 0, settlementDeferred: 0, unavailable: 0,
      globalUsage: { attempts, callLimit: 100000, remainingCalls: 100000 - attempts,
        resetAt: '2026-10-11T00:00:00+08:00', spent: .3, reserved: .02, costLimit: 0 },
      pricing: { currency: 'CNY', catalogEntries: 2, legacyChatPriceConfigured: false } })
    return send({})
  })
  await page.goto('/admin/monitor')
  await page.getByRole('button', { name: 'AI 调用', exact: true }).click()
  const monitor = page.locator('[aria-label="AI 预算监控"]')
  await expect(monitor).toContainText('85,000 / 100,000')
  await expect(monitor).toContainText('剩余 15,000')
  await expect(monitor).toContainText('85%')
  await expect(monitor).toContainText('CNY')
  await expect(monitor).toContainText('独立模型价格配置 2 项')
  attempts = 90000
  await monitor.getByRole('button', { name: '刷新预算指标' }).click()
  await expect(monitor).toContainText('90,000 / 100,000')
  await expect(monitor).toContainText('剩余 10,000')
})

test('budget notice refreshes and async quota failure preserves the user session', async ({ page }) => {
  let remaining = 5
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'budget-user', role: 'USER' }))
  })
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (url.pathname === '/api/profile/ai-budget') return send({ enabled: true, remainingCalls: remaining, callLimit: 100, resetAt: '2026-10-05T00:00:00+08:00', costLimit: .5, spent: .1, reserved: .02 })
    if (url.pathname === '/api/assistant/conversations') return send([])
    if (url.pathname === '/api/assistant/proactive') return send({ items: [] })
    if (url.pathname === '/api/meals/analysis-requests') return send({ key: route.request().headers()['idempotency-key'], status: 'PENDING', retryAfterSeconds: 1 })
    if (url.pathname.startsWith('/api/meals/analysis-requests/')) { remaining = 0;return send({ key: decodeURIComponent(url.pathname.split('/').pop()), status: 'FAILED', errorCode: 'AI_BUDGET_EXCEEDED' }) }
    return send(url.pathname.endsWith('/goals/current') ? { goalType: 'balanced' } : {})
  })
  await page.goto('/')
  const notice = page.getByRole('complementary', { name: 'AI 每日额度' })
  await expect(notice).toContainText('5 / 100')
  await expect(notice).toContainText('预留 0.0200')
  await page.getByLabel('选择饮食图片').setInputFiles({ name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('test-image') })
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await expect(page.getByRole('region', { name: '餐食分析进度' }).getByRole('status')).toContainText('重置后再使用原图重试')
  await expect(notice).toContainText('今日 AI 额度已用尽')
  expect(await page.evaluate(() => localStorage.getItem('mealcheck_token'))).toBeNull()
  remaining = 7
  await notice.getByRole('button', { name: '刷新额度' }).click()
  await expect(notice).toContainText('7 / 100')
  await page.locator('.assistant-fab').click()
  await expect(page.getByLabel('饮食助手聊天窗口').getByRole('complementary', { name: 'AI 每日额度' })).toContainText('7 / 100')
})
