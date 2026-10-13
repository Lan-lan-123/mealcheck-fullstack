import { test, expect } from '@playwright/test'

// Browser tests use a deterministic API contract. Real persistence, authorization,
// concurrency and migrations are covered separately by the backend tests.
const foods = [{ name: '米饭', category: 'staple', confidence: .9 }]
const result = { recordId: 1, revision: 0, recognition: { foods }, evaluation: { score: 21, summary: '餐食结构有待补充', riskTags: ['蔬菜不足'] }, advice: '增加蔬菜和蛋白质' }
const report = { periodStart: '2025-01-01', periodEnd: '2025-01-07', days: 7, totalMeals: 1, averageScore: 21,
  reportText: '固定日期周报', riskTotals: {}, nextWeekSuggestions: ['补充蔬菜'] }
const meal = { id: 1, revision: 0, foods, score: 21, summary: '列表当前页餐食', createdAt: '2026-09-30T12:00:00' }

async function fixture(page, options = {}) {
  let currentResult = structuredClone(result), record = structuredClone(meal), key, posts = 0, reads = 0
  const periodQueries = []
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    if (path === '/api/auth/captcha') return send({ captchaId: 'test', question: '1 + 2 = ?' })
    if (path === '/api/auth/login') return send({ token: 'fixture-token', username: 'alice', role: 'USER', displayName: '测试用户' })
    if (path === '/api/auth/logout') return send(null)
    if (path === '/api/meals/analysis-requests') {
      posts++; key = request.headers()['idempotency-key']; expect(key).toMatch(/^[\w-]{16,80}$/)
      if (options.lostResponse) return send({ message: '响应传输失败' }, 502)
      if (options.pending) return send({ key, status: 'PENDING', retryAfterSeconds: 2 }, 202)
      return send({ key, status: 'SUCCEEDED', result: currentResult })
    }
    if (path.startsWith('/api/meals/analysis-requests/')) return options.pending && ++reads === 1 ? send({ key, status: 'PROCESSING', retryAfterSeconds: 2 }, 202) : send({ key, status: 'SUCCEEDED', result: currentResult })
    if (path.endsWith('/corrections')) {
      const payload = request.postDataJSON()
      currentResult = { ...currentResult, revision: 1, recognition: { foods: payload.foods }, evaluation: { score: 90, summary: '结构完整', riskTags: [] } }
      record = { ...record, foods: payload.foods, revision: 1, score: 90, summary: '结构完整' }
      return send(currentResult)
    }
    if (path === '/api/meals/1' && request.method() === 'DELETE') { record = null; return route.fulfill({ status: 204 }) }
    if (path === '/api/meals') {
      if (options.expired?.()) return send({ message: '登录已失效' }, 401)
      if (url.searchParams.has('from')) {
        periodQueries.push(Object.fromEntries(url.searchParams))
        return send({ content: [{ ...meal, summary: '报告期间独立餐食', createdAt: '2025-01-02T12:00:00' }], totalElements: 1, totalPages: 1 })
      }
      return send({ content: record ? [record] : [], totalElements: record ? 1 : 0, totalPages: record ? 1 : 0 })
    }
    if (path === '/api/reports/weekly/latest') return send(report)
    if (path === '/api/reports/weekly/jobs') return send({ id: 9, status: 'SUCCEEDED', report })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/meals/trends') return send({ totalMeals: 1, averageScore: 21, dailyScores: [], commonFoods: [], commonRisks: [] })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    if (['/api/assistant/ask', '/api/assistant/stream'].includes(path)) {
      if (options.ask) return options.ask(route)
      return send({ conversationId: 1, answer: '固定测试回答' })
    }
    return send(null)
  })
  await page.goto('/')
  await page.getByRole('textbox').nth(0).fill('alice')
  await page.locator('input[type=password]').fill('test-password')
  await page.getByPlaceholder('答案').fill('3')
  await page.getByRole('button', { name: '用户登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  return { periodQueries, posts: () => posts }
}

test('login, upload, correct, weekly detail and delete remain consistent', async ({ page }) => {
  const state = await fixture(page)
  await page.locator('input[type=file]').setInputFiles({ name: 'meal.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('fixture') })
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '纠正识别结果', exact: true }).click()
  await page.getByRole('button', { name: '添加食物', exact: true }).click()
  await page.getByRole('textbox', { name: '食物名称 2' }).fill('青菜')
  await page.getByRole('combobox', { name: '食物分类 2' }).selectOption('vegetable')
  await page.getByRole('textbox', { name: '修改原因' }).fill('漏识别蔬菜')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(page.locator('.score-circle strong')).toHaveText('90')
  await page.getByRole('button', { name: '我的周报', exact: true }).click()
  await page.getByRole('button', { name: '查看周报详情' }).click()
  await expect(page.getByText('报告期间独立餐食')).toBeVisible()
  expect(state.periodQueries[0]).toMatchObject({ from: '2025-01-01', to: '2025-01-07', page: '0', size: '10' })
  await page.getByRole('button', { name: '返回周报概览', exact: true }).click()
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  await page.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('dialog', { name: '确认操作' }).getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('暂无饮食记录', { exact: true })).toBeVisible()
  expect(state.posts()).toBe(1)
})

test('reload recovers a saved analysis after a lost response without resubmission', async ({ page }) => {
  const state = await fixture(page, { lostResponse: true })
  await page.locator('input[type=file]').setInputFiles({ name: 'meal.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('fixture') })
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await expect(page.getByText('响应传输失败')).toBeVisible()
  await page.reload()
  await expect(page.getByText('已找回上次图片分析结果。')).toBeVisible()
  await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible()
  expect(state.posts()).toBe(1)
})

test('new conversation ignores a late answer from the previous conversation', async ({ page }) => {
  let release, entered
  const started = new Promise(resolve => { entered = resolve })
  const gate = new Promise(resolve => { release = resolve })
  await fixture(page, { ask: async route => {
    const question = route.request().postDataJSON().question
    if (question === '旧问题') { entered(); await gate }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ conversationId: question === '旧问题' ? 1 : 2, answer: question === '旧问题' ? '旧会话迟到回答' : '新会话正确回答' }) }).catch(() => {})
  } })
  await page.locator('.assistant-fab').click()
  const input = page.getByPlaceholder('问问怎么吃更合适')
  await input.fill('旧问题'); await page.getByRole('button', { name: '发送问题' }).click(); await started
  await page.getByRole('button', { name: '新对话', exact: true }).click()
  await input.fill('新问题'); await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('新会话正确回答')).toBeVisible()
  release()
  await expect(page.locator('.assistant-messages')).not.toContainText('旧会话迟到回答')
})

test('expired authentication returns to login', async ({ page }) => {
  let expired = false
  await fixture(page, { expired: () => expired })
  expired = true
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  await expect(page.getByRole('button', { name: '用户登录', exact: true })).toBeVisible()
})

test('mobile queued upload automatically completes and correction dialog keeps keyboard focus', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await fixture(page, { pending: true })
  await page.locator('input[type=file]').setInputFiles({ name: 'meal.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('fixture') })
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await expect(page.getByRole('region', { name: '餐食分析进度' }).getByText('图片已接收，正在排队', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible({ timeout: 10000 })
  expect(state.posts()).toBe(1)
  await page.getByRole('button', { name: '纠正识别结果', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '纠正识别结果' })
  const box = await dialog.boundingBox(); expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(390)
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: '保存并重新评分' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(dialog.getByRole('button', { name: '关闭', exact: true })).toBeFocused()
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeFocused()
})
