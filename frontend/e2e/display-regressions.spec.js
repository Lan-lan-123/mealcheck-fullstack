import { test, expect } from './fixtures/authTest.js'

async function workspace(page) {
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'tester', role: 'USER' }))
  })
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/profile/diet-preferences') return send({ style: 'OMNIVORE', avoidFoods: [], dislikedFoods: [], version: 0 })
    if (path === '/api/profile/ai-budget') return send({ enabled: true, callLimit: 100, remainingCalls: 88, resetAt: '2026-10-15T00:00:00+08:00' })
    if (path === '/api/assistant/conversations') return send(Array.from({ length: 5 }, (_, i) => ({ id: i + 1, title: `饮食复盘对话 ${i + 1}` })))
    if (path === '/api/assistant/proactive') return send({ items: [
      '最近记录中的蔬菜种类比较单一，下一餐可以换一种深色蔬菜。',
      '午餐可以搭配一份鱼肉、鸡肉或豆制品。',
      '减少含糖饮料频率，优先选择白水。',
      '保持规律记录，帮助观察饮食习惯。'
    ] })
    if (path === '/api/assistant/stream') return route.fulfill({ contentType: 'text/event-stream', body: `event: complete\ndata: ${JSON.stringify({ answer: '下一餐可以增加一份蔬菜。' })}\n\n` })
    if (path === '/api/profile/behavior-goals') return send({ content: [], page: 0, totalPages: 0, totalElements: 0 })
    return send(null)
  })
}

test('partial answer is labeled after SSE and history reload and cannot be saved as a complete plan', async ({ page }) => {
  await workspace(page)
  const result = { answer: '已确认有依据的部分。其余未确认的信息暂不作结论。', answerStatus: 'PARTIAL', answerReasonCodes: ['UNSUPPORTED_STRENGTH'], deliveryStatus: 'COMPLETE', messageId: 8, conversationId: 1 }
  await page.route('**/api/assistant/stream', route => route.fulfill({ contentType: 'text/event-stream', body: `event: complete\ndata: ${JSON.stringify(result)}\n\n` }))
  await page.route('**/api/assistant/conversations/1*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 1, messages: [{ ...result, role: 'assistant', text: result.answer }] }) }))
  await page.goto('/user/overview')
  await page.locator('.assistant-fab').click()
  await page.getByPlaceholder('问问怎么吃更合适').fill('低因是否没有咖啡因？')
  await page.getByRole('button', { name: '发送问题' }).click()
  const answer = page.locator('.assistant-message').filter({ hasText: '已确认有依据的部分' })
  await expect(answer.getByRole('status')).toContainText('本次仅给出有依据的部分内容')
  await expect(answer.getByRole('button', { name: '加入计划', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '新对话', exact: true }).click()
  await page.getByRole('button', { name: '饮食复盘对话 1', exact: true }).click()
  await expect(answer.getByRole('status')).toContainText('本次仅给出有依据的部分内容')
  await expect(answer.getByRole('button', { name: '加入计划', exact: true })).toHaveCount(0)
})

test('review choice is submitted and unreviewed answers stay labeled in history', async ({ page }) => {
  await workspace(page)
  const sent = []
  const result = { answer: '快速候选回答。', reviewStatus: 'NOT_REVIEWED', riskLevel: 'LOW', answerStatus: 'COMPLETE', deliveryStatus: 'COMPLETE', messageId: 9, conversationId: 1 }
  await page.route('**/api/assistant/stream', route => {
    sent.push(route.request().postDataJSON())
    return route.fulfill({ contentType: 'text/event-stream', body: `event: complete\ndata: ${JSON.stringify(result)}\n\n` })
  })
  await page.route('**/api/assistant/conversations/1*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 1, messages: [{ ...result, role: 'assistant', text: result.answer }] }) }))
  await page.goto('/user/overview'); await page.locator('.assistant-fab').click()
  await expect(page.getByRole('combobox', { name: '回答方式' })).toHaveValue('REVIEWED')
  await page.getByRole('combobox', { name: '回答方式' }).selectOption('FAST')
  await page.getByPlaceholder('问问怎么吃更合适').fill('低因咖啡是否没有咖啡因？')
  await page.getByRole('button', { name: '发送问题' }).click()
  const answer = page.locator('.assistant-message').filter({ hasText: '快速候选回答' })
  await expect(answer.getByRole('status')).toContainText('未经过模型证据复核')
  expect(sent[0].reviewMode).toBe('FAST')
  await expect(answer.locator('.assistant-risk')).toHaveCount(0)
  await page.getByRole('button', { name: '新对话', exact: true }).click()
  await page.getByRole('button', { name: '饮食复盘对话 1', exact: true }).click()
  await expect(answer.getByRole('status')).toContainText('未经过模型证据复核')
})

async function expectComposerInsidePanel(page) {
  const panel = await page.locator('.assistant-panel').boundingBox()
  const input = await page.getByPlaceholder('问问怎么吃更合适').boundingBox()
  const send = await page.getByRole('button', { name: '发送问题' }).boundingBox()
  for (const control of [input, send]) {
    expect(control.y).toBeGreaterThanOrEqual(panel.y)
    expect(control.y + control.height).toBeLessThanOrEqual(panel.y + panel.height)
    expect(control.x).toBeGreaterThanOrEqual(panel.x)
    expect(control.x + control.width).toBeLessThanOrEqual(panel.x + panel.width)
  }
  expect((await page.locator('.assistant-messages').boundingBox()).height).toBeGreaterThanOrEqual(64)
}

for (const viewport of [{ width: 320, height: 844 }, { width: 390, height: 844 }, { width: 390, height: 420 }, { width: 1366, height: 768 }]) {
  test(`assistant keeps its composer usable with budget and suggestions at ${viewport.width}x${viewport.height}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    await workspace(page)
    await page.goto('/user/overview')
    await page.locator('.assistant-fab').click()
    await expect(page.locator('.assistant-panel .ai-budget-notice')).toContainText('88 / 100')
    await expect(page.locator('.assistant-proactive span')).toHaveCount(4)
    await expectComposerInsidePanel(page)
    const greeting = page.locator('.assistant-message').first()
    await greeting.scrollIntoViewIfNeeded()
    await expect(greeting).toBeInViewport()
    await expectComposerInsidePanel(page)
    await page.locator('.assistant-messages').evaluate(element => { element.scrollTop = 0 })
    await page.screenshot({ path: testInfo.outputPath('assistant-layout.png') })
    await page.getByPlaceholder('问问怎么吃更合适').fill('下一餐怎么搭配？')
    await page.getByRole('button', { name: '发送问题' }).click()
    const answer = page.locator('.assistant-message').filter({ hasText: '下一餐可以增加一份蔬菜。' })
    await expect(answer).toBeVisible()
    await answer.scrollIntoViewIfNeeded()
    await expectComposerInsidePanel(page)
    if (viewport.width === 390 && viewport.height === 844) {
      await page.setViewportSize({ width: 390, height: 420 })
      await expectComposerInsidePanel(page)
    }
  })
}

test('short assistant viewport keeps cancellation and input visible during a pending reply', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 420 })
  await workspace(page)
  let release
  const pending = new Promise(resolve => { release = resolve })
  await page.route('**/api/assistant/stream', async route => {
    await pending
    await route.fulfill({ contentType: 'text/event-stream', body: '' }).catch(() => {})
  })
  try {
    await page.goto('/user/overview')
    await page.locator('.assistant-fab').click()
    await page.getByPlaceholder('问问怎么吃更合适').fill('下一餐怎么搭配？')
    await page.getByRole('button', { name: '发送问题' }).click()
    const cancel = page.getByRole('button', { name: '取消等待', exact: true })
    await expect(cancel).toBeInViewport({ ratio: 1 })
    await expectComposerInsidePanel(page)
    await cancel.click()
    await expect(cancel).toHaveCount(0)
    await expect(page.getByPlaceholder('问问怎么吃更合适')).toBeEnabled()
    await expectComposerInsidePanel(page)
  } finally { release() }
})

test('navigation and detail close icons retain their intended width on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await workspace(page)
  await page.route('**/api/meals/summaries*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ content: [{ id: 1, createdAt: '2026-10-14T04:00:00', score: 88, summary: '测试餐食' }], totalElements: 1, totalPages: 1, page: 0 }) }))
  await page.route('**/api/meals/1', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 1, createdAt: '2026-10-14T04:00:00', score: 88, foods: [] }) }))
  await page.goto('/user/records')
  const menu = page.getByRole('button', { name: '打开导航菜单' })
  expect((await menu.locator('svg').boundingBox()).width).toBeGreaterThanOrEqual(20)
  await menu.click()
  const closeMenu = page.getByRole('button', { name: '关闭导航菜单' })
  expect((await closeMenu.locator('svg').boundingBox()).width).toBeGreaterThanOrEqual(20)
  await closeMenu.click()
  await page.getByRole('button', { name: '查看餐食详情' }).click()
  const drawer = page.getByRole('dialog', { name: '餐食记录详情', exact: true })
  await expect(drawer).toBeVisible()
  expect((await drawer.getByRole('button', { name: '关闭详情' }).locator('svg').boundingBox()).width).toBeGreaterThanOrEqual(20)
})

test('behavior goals clear a failed-page error when another page loads successfully', async ({ page }) => {
  await workspace(page)
  await page.route('**/api/profile/behavior-goals*', route => {
    const current = Number(new URL(route.request().url()).searchParams.get('page'))
    if (current === 2) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '目标列表暂时不可用' }) })
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ content: [], page: current, totalPages: 3, totalElements: 60 }) })
  })
  await page.goto('/user/goals')
  const pagination = page.getByRole('navigation', { name: '行为目标分页' })
  await expect(pagination).toContainText('第 1 / 3 页')
  await pagination.getByRole('button', { name: '下一页' }).click()
  await expect(pagination).toContainText('第 2 / 3 页')
  await pagination.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByRole('alert')).toHaveText('目标列表暂时不可用')
  await pagination.getByRole('button', { name: '上一页' }).click()
  await expect(pagination).toContainText('第 1 / 3 页')
  await expect(page.getByRole('alert')).toHaveCount(0)
})
