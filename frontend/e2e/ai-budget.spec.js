import { test, expect } from '@playwright/test'

test('budget notice refreshes and async quota failure preserves the user session', async ({ page }) => {
  let remaining = 5
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'budget-test-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'budget-user', role: 'USER' }))
  })
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (url.pathname === '/api/profile/ai-budget') return send({ enabled: true, remainingCalls: remaining, callLimit: 100, resetAt: '2026-10-05T00:00:00+08:00', costLimit: .5, spent: .1, reserved: .02 })
    if (url.pathname === '/api/assistant/conversations') return send([])
    if (url.pathname === '/api/assistant/proactive') return send({ items: [] })
    if (url.pathname === '/api/meals/analysis-requests') return send({ key: 'budget-analysis-key', status: 'PENDING', retryAfterSeconds: 1 })
    if (url.pathname === '/api/meals/analysis-requests/budget-analysis-key') { remaining = 0;return send({ key: 'budget-analysis-key', status: 'FAILED', errorCode: 'AI_BUDGET_EXCEEDED' }) }
    return send(url.pathname.endsWith('/goals/current') ? { goalType: 'balanced' } : {})
  })
  await page.goto('/')
  const notice = page.getByRole('complementary', { name: 'AI 每日额度' })
  await expect(notice).toContainText('5 / 100')
  await expect(notice).toContainText('预留 0.0200')
  await page.getByLabel('选择饮食图片').setInputFiles({ name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('test-image') })
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '重置后使用原图重试' })).toBeVisible()
  await expect(notice).toContainText('今日 AI 额度已用尽')
  expect(await page.evaluate(() => localStorage.getItem('mealcheck_token'))).toBe('budget-test-token')
  remaining = 7
  await notice.getByRole('button', { name: '刷新额度' }).click()
  await expect(notice).toContainText('7 / 100')
  await page.locator('.assistant-fab').click()
  await expect(page.getByLabel('饮食助手聊天窗口').getByRole('complementary', { name: 'AI 每日额度' })).toContainText('7 / 100')
})
