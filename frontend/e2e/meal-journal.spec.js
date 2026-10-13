import { test, expect } from '@playwright/test'
import { createServer } from 'node:http'

async function fixture(page) {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const state = { records: [], goals: [], plans: [], feedback: null, skipped: false, writes: [], cancelled: [], reads: [] }
  const summary = () => ({ periodStart: today, periodEnd: today, totalMeals: state.records.length, recordedDays: state.records.length ? 1 : 0, days: 1, coveragePercent: state.records.length ? 100 : 0, confirmedSkippedSlots: state.skipped ? 1 : 0, unknownSlots: 3, goals: [], plans: { OPEN: 0, DONE: 0, SKIPPED: 0 }, explanation: '未记录表示未知；营养估算不参与达标判断。', daily: [{ day: today, meals: state.records.length, slots: { BREAKFAST: state.skipped ? 'SKIPPED' : 'UNKNOWN', LUNCH: 'UNKNOWN', DINNER: 'UNKNOWN', SNACK: 'UNKNOWN' } }] })
  await page.addInitScript(() => { localStorage.setItem('mealcheck_token', 'journal-fixture-token'); localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'journal', displayName: '测试用户', role: 'USER' })) })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (method === 'GET') state.reads.push({ path, query: Object.fromEntries(new URL(request.url()).searchParams) })
    if (method !== 'GET') state.writes.push({ path, body: request.postData() ? request.postDataJSON() : null })
    if (path === '/api/meals/manual') {
      const body = request.postDataJSON(); state.records.push({ ...body, id: 1, occurredAt: `${body.occurredAt}:00+08:00`, createdAt: new Date().toISOString(), score: 80, summary: '手动餐食', recordSource: 'MANUAL', timeConfirmed: true, metadataVersion: 0, revision: 0, advice: '午餐加一份蔬菜', categoryCounts: {}, riskTags: [] }); return send(state.records[0])
    }
    if (path === '/api/meals/1/metadata') { const body = request.postDataJSON(); state.records[0] = { ...state.records[0], ...body, occurredAt: `${body.occurredAt}:00+08:00`, metadataVersion: state.records[0].metadataVersion + 1 }; return send(state.records[0]) }
    if (path === '/api/meals') return send({ content: state.records, totalElements: state.records.length, totalPages: 1, page: 0 })
    if (path === '/api/meals/skipped') { state.skipped = method !== 'DELETE'; return send(null) }
    if (path === '/api/meals/coverage') return send(summary())
    const paged = items => { const query = new URL(request.url()).searchParams, page = Number(query.get('page') || 0), size = Number(query.get('size') || 20); return { content: items.slice(page * size, (page + 1) * size), page, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) } }
    if (path === '/api/profile/behavior-goals') { if (method === 'POST') { const goal = { ...request.postDataJSON(), id: state.goals.length + 1, version: 0 }; state.goals.push(goal); return send(goal) } return send(paged(state.goals)) }
    if (path.startsWith('/api/profile/behavior-goals/')) { const id = Number(path.split('/').pop()), index = state.goals.findIndex(g => g.id === id); state.goals[index] = { ...state.goals[index], ...request.postDataJSON(), version: state.goals[index].version + 1 }; return send(state.goals[index]) }
    if (path === '/api/plans') { if (method === 'POST') { const plan = { ...request.postDataJSON(), id: state.plans.length + 3, status: 'OPEN', version: 0 }; state.plans.push(plan); return send(plan) } return send(paged(state.plans)) }
    if (path.startsWith('/api/plans/')) { if (state.failWrite) return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: '版本冲突' }) }); const id = Number(path.split('/').pop()), index = state.plans.findIndex(p => p.id === id); state.plans[index] = { ...state.plans[index], ...request.postDataJSON(), version: state.plans[index].version + 1 }; return send(state.plans[index]) }
    if (path === '/api/reports/weekly/latest') return send({ days: 7, periodStart: today, periodEnd: today, totalMeals: 0, averageScore: 0, reportText: '当前周报', tracking: { ...summary(), plans: { OPEN: state.plans.filter(p => p.status === 'OPEN').length, DONE: state.plans.filter(p => p.status === 'DONE').length, SKIPPED: state.plans.filter(p => p.status === 'SKIPPED').length }, goals: state.goals.filter(g => g.active).map(g => ({ goal: g, completed: 0, remaining: g.target, achieved: false, periodStart: today, periodEnd: today, eligibleDays: 1, daysAchieved: 0 })) } })
    if (path === '/api/reports/weekly/history') return send(paged(state.history || []))
    if (path.startsWith('/api/reports/weekly/history/')) return send({ snapshot: { id: 10, stale: true }, report: { reportText: '原始历史报告内容', totalMeals: 8, averageScore: 82, nextWeekSuggestions: ['原建议'] } })
    if (path === '/api/assistant/messages/9/feedback') { if (method === 'PUT') state.feedback = request.postDataJSON(); return send(state.feedback) }
    if (path.startsWith('/api/assistant/requests/')) { state.cancelled.push(path); return send(null) }
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    if (path === '/api/profile/ai-budget') return send({ enabled: false })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path.includes('/nutrition')) return send({ enabled: false, items: [], status: 'DISABLED' })
    return send(null)
  })
  return { state, today }
}

test('manual backdate, metadata edit and explicit skipped slot are separate flows on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { state, today } = await fixture(page)
  await page.goto('/user/records')
  await expect(page.getByText('未记录，情况未知').first()).toBeVisible()
  await page.getByRole('button', { name: '确认这餐未吃' }).first().click()
  await expect(page.getByRole('button', { name: '撤销未吃标记' })).toBeVisible()
  await page.getByRole('button', { name: '撤销未吃标记' }).click()
  await page.getByRole('button', { name: '手动记录 / 补记' }).click()
  const modal = page.getByRole('dialog', { name: '手动记录与补记' })
  await modal.getByLabel('实际用餐时间').fill(`${today}T00:01`)
  await modal.getByLabel('餐次', { exact: true }).selectOption('LUNCH')
  await modal.getByLabel('手动食物名称 1').fill('青菜')
  await modal.getByLabel('手动食物分类 1').selectOption('vegetable')
  await modal.getByRole('button', { name: '保存用餐记录' }).click()
  await expect(page.locator('.record-list')).toContainText('手动餐食')
  await page.getByRole('button', { name: '修改用餐信息' }).click()
  const edit = page.getByRole('dialog', { name: '修改用餐信息' })
  await edit.getByLabel('餐次', { exact: true }).selectOption('DINNER')
  await edit.getByRole('button', { name: '保存用餐记录' }).click()
  await expect(page.locator('.record-list')).toContainText('晚餐')
  expect(state.writes.find(w => w.path.endsWith('/metadata')).body.expectedVersion).toBe(0)
  expect(state.writes.some(w => w.path.includes('/analysis'))).toBe(false)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/journal-mobile.png', fullPage: true })
})

test('behavior goal and suggestion plan support saving, completion, skipping and adjustment', async ({ page }) => {
  const { state } = await fixture(page)
  await page.goto('/user/goals')
  await page.getByLabel('目标类型').selectOption('MEALS_DAILY')
  await page.getByLabel('目标数量').fill('3')
  await page.getByRole('button', { name: '保存行为目标' }).click()
  await expect(page.locator('.behavior-goal-list')).toContainText('每天记录 3 个餐次')
  expect(state.goals[0].kind).toBe('MEALS_DAILY')
  await page.getByRole('button', { name: '行动计划', exact: true }).click()
  await page.getByRole('button', { name: '加入计划' }).click()
  await page.getByLabel('计划标题').fill('每餐增加蔬菜')
  await page.getByLabel('具体行动').fill('午餐加一份蔬菜')
  await page.getByRole('button', { name: '保存行动计划' }).click()
  await expect(page.locator('.plan-list')).toContainText('待执行')
  await page.getByRole('button', { name: '完成打卡' }).click()
  await expect(page.locator('.plan-list')).toContainText('已完成')
  await page.getByRole('button', { name: '跳过', exact: true }).click()
  await expect(page.locator('.plan-list')).toContainText('已跳过')
  await page.getByRole('button', { name: '调整计划' }).click()
  await page.getByLabel('执行备注').fill('外出用餐，明天调整')
  await page.getByRole('button', { name: '保存调整' }).click()
  await expect(page.locator('.plan-list')).toContainText('外出用餐，明天调整')
  expect(state.plans[0].version).toBe(3)
})

async function streamServer(page, complete, queuedOnly = false) {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const server = createServer(async (req, res) => {
    req.resume()
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*' })
    const send = (event, data) => res.write(`event:${event}\ndata:${JSON.stringify(data)}\n\n`)
    if (queuedOnly) send('queued', { requestId: 'stream-fixture', status: 'QUEUED' })
    else {
      send('meta', { requestId: 'stream-fixture', conversationId: 8 })
      send('delta', { text: '先看到这部分建议' })
    }
    await gate
    if (complete) send('complete', { answer: '先看到这部分建议，再增加蔬菜。', summary: '先看到这部分建议，再增加蔬菜。', conversationId: 8, messageId: 9, requestId: 'stream-fixture', deliveryStatus: 'COMPLETE', references: [] })
    res.end()
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  await page.route('**/api/assistant/stream', route => route.continue({ url: `http://127.0.0.1:${server.address().port}/stream` }))
  return { release, close: async () => { release(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) } }
}

test('SSE is visible before completion and finished answer can receive feedback and become a plan', async ({ page }) => {
  const { state } = await fixture(page)
  const stream = await streamServer(page, true)
  try {
    await page.goto('/user/overview')
    await page.locator('.assistant-fab').click()
    await page.getByPlaceholder('问问怎么吃更合适').fill('午餐怎么吃')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.locator('.assistant-message').last()).toContainText('正在整理饮食建议')
    await expect(page.getByText('先看到这部分建议', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '取消等待' })).toBeVisible()
    expect(state.feedback).toBe(null)
    stream.release()
    await expect(page.getByRole('button', { name: '评价这条回答' })).toBeVisible()
    await page.getByRole('button', { name: '评价这条回答' }).click()
    await page.getByLabel('是否有帮助').selectOption('UNHELPFUL')
    await page.getByLabel('问题类型').selectOption('INCORRECT')
    await page.getByRole('button', { name: '提交回答反馈' }).click()
    await expect(page.getByText('已提交反馈')).toBeVisible()
    expect(state.feedback.category).toBe('INCORRECT')
    await page.getByRole('button', { name: '加入计划' }).click()
    await page.getByRole('button', { name: '保存行动计划' }).click()
    expect(state.plans[0].sourceMessageId).toBe(9)
  } finally { await stream.close() }
})

test('cancel sends the owned request ID and retains partial content without completed answer actions', async ({ page }) => {
  const { state } = await fixture(page)
  const stream = await streamServer(page, false)
  try {
    await page.goto('/user/overview')
    await page.locator('.assistant-fab').click()
    await page.getByPlaceholder('问问怎么吃更合适').fill('午餐怎么吃')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByText('先看到这部分建议', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '取消等待' }).click()
    await expect(page.getByText('请求 ID：stream-fixture · 已取消，内容未完成')).toBeVisible()
    await expect.poll(() => state.cancelled.length).toBeGreaterThan(0)
    await expect(page.getByRole('button', { name: '加入计划' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled()
  } finally { await stream.close() }
})

test('manual plans can be added repeatedly and reset their draft after success', async ({ page }) => {
  const { state } = await fixture(page)
  await page.goto('/user/plans')
  await page.getByRole('button', { name: '加入计划' }).click()
  await page.getByLabel('计划标题').fill('第一项计划')
  await page.getByLabel('具体行动').fill('早餐加蔬菜')
  await page.getByRole('button', { name: '保存行动计划' }).click()
  await expect(page.locator('.plan-list')).toContainText('第一项计划')
  await page.getByRole('button', { name: '继续新增计划' }).click()
  await expect(page.getByLabel('具体行动')).toHaveValue('')
  await page.getByLabel('计划标题').fill('第二项计划')
  await page.getByLabel('具体行动').fill('午餐加蛋白质')
  await page.getByRole('button', { name: '保存行动计划' }).click()
  await expect(page.locator('.plan-list')).toContainText('第二项计划')
  expect(state.plans).toHaveLength(2)
})

test('queued replies receive their cancellation ID before model work begins', async ({ page }) => {
  const { state } = await fixture(page)
  const stream = await streamServer(page, false, true)
  try {
    await page.goto('/user/overview')
    await page.locator('.assistant-fab').click()
    await page.getByPlaceholder('问问怎么吃更合适').fill('排队中的问题')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByText('正在排队等待回复')).toBeVisible()
    await page.getByRole('button', { name: '取消等待' }).click()
    await expect.poll(() => state.cancelled).toContain('/api/assistant/requests/stream-fixture')
    await expect(page.getByText('请求 ID：stream-fixture · 已取消，内容未完成')).toBeVisible()
  } finally { await stream.close() }
})

test('goals and plans paginate with totals and edit IDs on later pages', async ({ page }) => {
  const { state, today } = await fixture(page)
  state.goals = Array.from({ length: 41 }, (_, i) => ({ id: i + 1, kind: 'MEALS_DAILY', target: 1, startDate: today, endDate: today, active: false, version: 0 }))
  state.plans = Array.from({ length: 41 }, (_, i) => ({ id: i + 3, title: `计划 ${i + 1}`, actionText: '具体行动', sourceType: 'MANUAL', plannedDate: today, status: 'OPEN', version: 0 }))
  await page.goto('/user/goals')
  const goals = page.getByRole('navigation', { name: '行为目标分页' })
  await expect(goals).toContainText('共 41 条')
  await goals.getByRole('button', { name: '下一页' }).click()
  await expect(goals).toContainText('第 2 / 3 页')
  await page.locator('.behavior-goal-list').getByRole('button', { name: '调整目标' }).first().click()
  await page.getByLabel('目标数量').fill('3')
  await page.getByRole('button', { name: '保存行为目标' }).click()
  await expect.poll(() => state.goals[20].target).toBe(3)
  expect(state.writes.at(-1).path).toBe('/api/profile/behavior-goals/21')
  await page.getByRole('button', { name: '行动计划', exact: true }).click()
  const plans = page.getByRole('navigation', { name: '行动计划分页' })
  await expect(plans).toContainText('共 41 条')
  await plans.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByRole('heading', { name: '计划 21', exact: true })).toBeVisible()
  await plans.getByRole('button', { name: '下一页' }).click()
  await expect(plans).toContainText('第 3 / 3 页')
  await expect(plans.getByRole('button', { name: '下一页' })).toBeDisabled()
})

test('plan and goal mutations immediately invalidate a recently viewed report', async ({ page }) => {
  const { state, today } = await fixture(page)
  state.plans = [{ id: 3, title: '执行计划', actionText: '增加蔬菜', sourceType: 'MANUAL', plannedDate: today, status: 'OPEN', version: 0 }]
  await page.goto('/user/reports')
  await expect(page.locator('.tracking-summary')).toContainText('完成 0')
  const reports = () => state.reads.filter(r => r.path === '/api/reports/weekly/latest').length
  expect(reports()).toBe(1)
  await page.getByRole('button', { name: '行动计划', exact: true }).click()
  await page.getByRole('button', { name: '完成打卡' }).click()
  await expect(page.locator('.plan-list')).toContainText('已完成')
  await page.getByRole('button', { name: '我的周报', exact: true }).click()
  await expect(page.locator('.tracking-summary')).toContainText('完成 1')
  expect(reports()).toBe(2)
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  await page.getByLabel('目标类型').selectOption('MEALS_DAILY')
  await page.getByLabel('目标数量').fill('3')
  await page.getByRole('button', { name: '保存行为目标' }).click()
  await expect(page.locator('.behavior-goal-list')).toContainText('每天记录 3 个餐次')
  await page.getByRole('button', { name: '我的周报', exact: true }).click()
  await expect(page.locator('.tracking-summary')).toContainText('0/3')
  expect(reports()).toBe(3)
})

test('failed mutations leave a still valid report cache untouched', async ({ page }) => {
  const { state, today } = await fixture(page)
  state.plans = [{ id: 3, title: '冲突计划', actionText: 'action', sourceType: 'MANUAL', plannedDate: today, status: 'OPEN', version: 0 }]
  await page.goto('/user/reports')
  await expect(page.locator('.tracking-summary')).toContainText('完成 0')
  await page.getByRole('button', { name: '行动计划', exact: true }).click()
  state.failWrite = true
  await page.getByRole('button', { name: '完成打卡' }).click()
  await expect(page.getByRole('alert')).toContainText('版本冲突')
  await page.getByRole('button', { name: '我的周报', exact: true }).click()
  await expect(page.locator('.tracking-summary')).toContainText('完成 0')
  expect(state.reads.filter(r => r.path === '/api/reports/weekly/latest')).toHaveLength(1)
})

test('stale historical reports retain their original text in a read-only snapshot view', async ({ page }) => {
  const { state, today } = await fixture(page)
  state.history = [{ id: 10, days: 7, periodStart: today, periodEnd: today, generatedAt: new Date().toISOString(), stale: true }]
  await page.goto('/user/reports')
  await expect(page.getByText('已过期，原快照保留', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '查看这份历史周报' }).click()
  const drawer = page.getByRole('dialog', { name: '历史周报快照' })
  await expect(drawer).toContainText('原始历史报告内容')
  await expect(drawer).toContainText('内容不会自动改写')
})
