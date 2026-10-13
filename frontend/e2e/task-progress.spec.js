import { test, expect } from '@playwright/test'

const result = { recordId: 1, recognition: { foods: [{ name: '米饭', category: 'staple' }] }, evaluation: { score: 80, summary: '餐食搭配良好', riskTags: [] }, advice: '继续保持' }
const report = { periodStart: '2026-10-01', periodEnd: '2026-10-07', totalMeals: 3, averageScore: 80, reportText: '本周饮食总结', riskTotals: {}, nextWeekSuggestions: [] }
const image = { name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFncAAAAASUVORK5CYII=', 'base64') }

async function setup(page, handler) {
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'progress-test-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'demo', role: 'USER', displayName: '测试用户' }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    if (await handler({ route, request, path, send })) return
    return send(path === '/api/profile/goals/current' ? { goalType: 'balanced' }
      : path === '/api/assistant/conversations' ? [{ id: 7, title: '历史对话' }]
      : path === '/api/assistant/proactive' ? { items: [] }
      : path === '/api/meals' ? { content: [], totalPages: 0 }
      : null)
  })
  await page.goto('/user/overview')
}

async function startAnalysis(page) {
  await page.locator('input[type=file]').setInputFiles(image)
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
}

test('meal stages follow backend status, survive reload without resubmission, and fit mobile', async ({ page }) => {
  let release, status = 'PENDING', key, posts = 0
  const gate = new Promise(resolve => { release = resolve })
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page, async ({ path, request, send }) => {
    if (path === '/api/meals/analysis-requests') {
      posts++; key = request.headers()['idempotency-key']; await gate
      await send({ key, status, retryAfterSeconds: 2 }, 202); return true
    }
    if (path.startsWith('/api/meals/analysis-requests/')) {
      await send({ key, status, retryAfterSeconds: 2, ...(status === 'SUCCEEDED' ? { result } : {}) }); return true
    }
  })
  await startAnalysis(page)
  const progress = page.getByRole('region', { name: '餐食分析进度' })
  await expect(progress.getByText('正在上传图片', { exact: true })).toBeVisible()
  await expect(progress.getByRole('progressbar')).not.toHaveAttribute('aria-valuenow')
  await expect(progress.locator('[aria-current="step"]')).toHaveText('1上传图片')
  release()
  await expect(progress.getByText('图片已接收，正在排队', { exact: true })).toBeVisible()
  await page.reload()
  await expect(progress.locator('[aria-current="step"]')).toHaveText('2等待处理')
  status = 'PROCESSING'
  await expect(progress.getByText('正在分析餐食', { exact: true })).toBeVisible({ timeout: 10000 })
  await expect(progress.locator('[aria-current="step"]')).toHaveText('3智能分析')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/progress-meal-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: 'test-results/progress-meal-desktop.png', fullPage: true })
  status = 'SUCCEEDED'
  await expect(progress.getByText('餐食分析已完成', { exact: true })).toBeVisible({ timeout: 10000 })
  await expect(progress.getByRole('progressbar')).toHaveCount(0)
  await expect(progress.locator('.is-complete')).toHaveCount(4)
  await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible()
  expect(posts).toBe(1)
})

test('meal network interruption is visible and reconnects without another upload', async ({ page }) => {
  let reads = 0, posts = 0
  await setup(page, async ({ path, request, route, send }) => {
    if (path === '/api/meals/analysis-requests') {
      posts++; await send({ key: request.headers()['idempotency-key'], status: 'PROCESSING', retryAfterSeconds: 2 }); return true
    }
    if (path.startsWith('/api/meals/analysis-requests/')) {
      if (++reads === 1) await route.abort('failed')
      else await send({ status: 'SUCCEEDED', result })
      return true
    }
  })
  await startAnalysis(page)
  const progress = page.getByRole('region', { name: '餐食分析进度' })
  await expect(progress.getByText('连接暂时中断，正在重新查询', { exact: true })).toBeVisible({ timeout: 10000 })
  await expect(progress.getByRole('progressbar')).toBeVisible()
  await expect(progress.getByText('餐食分析已完成', { exact: true })).toBeVisible({ timeout: 10000 })
  expect(posts).toBe(1)
})

test('weekly progress shows queue, retry and completion across navigation', async ({ page }) => {
  let status = 'PENDING', posts = 0
  await setup(page, async ({ path, send }) => {
    if (path === '/api/reports/weekly/latest') { await send(report); return true }
    if (path === '/api/reports/weekly/jobs') { posts++; await send({ id: 9, status }); return true }
    if (path === '/api/reports/weekly/jobs/9') {
      await send({ id: 9, status, ...(status === 'SUCCEEDED' ? { report } : {}) }); return true
    }
  })
  await page.getByRole('button', { name: '我的周报', exact: true }).click()
  await page.getByRole('button', { name: '生成今日周报 / 查看进度' }).click()
  const progress = page.getByRole('region', { name: '周报生成进度' })
  await expect(progress.getByText('周报正在排队', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看周报详情' })).toBeDisabled()
  status = 'RUNNING'
  await expect(progress.getByText('正在生成周报', { exact: true })).toBeVisible({ timeout: 10000 })
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  status = 'RETRY'
  await page.getByRole('button', { name: '我的周报', exact: true }).click()
  await expect(progress.getByText('周报正在自动重试', { exact: true })).toBeVisible({ timeout: 10000 })
  await page.screenshot({ path: 'test-results/progress-weekly-retry.png', fullPage: true })
  status = 'SUCCEEDED'
  await expect(progress.getByText('周报已生成', { exact: true })).toBeVisible({ timeout: 10000 })
  await expect(progress.getByRole('progressbar')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查看周报详情' })).toBeEnabled()
  expect(posts).toBe(1)
})

test('a failed analysis stops animation and offers result recovery', async ({ page }) => {
  await setup(page, async ({ path, send }) => {
    if (path === '/api/meals/analysis-requests') { await send({ message: '请求暂时不可用' }, 502); return true }
  })
  await startAnalysis(page)
  const progress = page.getByRole('region', { name: '餐食分析进度' })
  await expect(progress.getByText('暂未取得分析结果', { exact: true })).toBeVisible()
  await expect(progress.getByText('请求暂时不可用', { exact: true })).toBeVisible()
  await expect(progress.getByRole('progressbar')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查询上次分析结果' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '开始分析', exact: true })).toBeEnabled()
})

test('assistant distinguishes history from a reply, handles long waits and reduced motion', async ({ page }) => {
  await page.clock.install()
  await page.emulateMedia({ reducedMotion: 'reduce' })
  let releaseReply, releaseHistory
  const replyGate = new Promise(resolve => { releaseReply = resolve })
  const historyGate = new Promise(resolve => { releaseHistory = resolve })
  await setup(page, async ({ path, send }) => {
    if (['/api/assistant/ask', '/api/assistant/stream'].includes(path)) { await replyGate; await send({ conversationId: 8, answer: '生成完成的建议' }); return true }
    if (path === '/api/assistant/conversations/7') { await historyGate; await send({ id: 7, messages: [{ role: 'assistant', text: '历史消息内容' }] }); return true }
  })
  await page.locator('.assistant-fab').click()
  await page.getByPlaceholder('问问怎么吃更合适').fill('今天怎么吃？')
  await page.getByRole('button', { name: '发送问题' }).click()
  const progress = page.getByRole('region', { name: '饮食助手进度' })
  await expect(progress.getByText('正在整理饮食建议', { exact: true })).toBeVisible()
  await page.clock.fastForward(31000)
  await expect(progress.getByText(/等待时间较长/)).toBeVisible()
  expect(await progress.locator('.task-progress-track > span').evaluate(el => getComputedStyle(el).animationName)).toBe('none')
  await page.locator('.assistant-fab').click()
  await page.clock.fastForward(3000)
  await page.locator('.assistant-fab').click()
  await expect(progress.getByText(/已等待 34 秒/)).toBeVisible()
  await page.screenshot({ path: 'test-results/progress-assistant.png', fullPage: true })
  releaseReply()
  await expect(page.getByText('生成完成的建议', { exact: true })).toBeVisible()
  await expect(progress).toHaveCount(0)
  await page.getByRole('button', { name: '历史对话', exact: true }).click()
  await expect(progress.getByText('正在加载历史对话', { exact: true })).toBeVisible()
  await expect(progress.getByText('正在整理饮食建议', { exact: true })).toHaveCount(0)
  releaseHistory()
  await expect(page.getByText('历史消息内容', { exact: true })).toBeVisible()
  await expect(progress).toHaveCount(0)
})
