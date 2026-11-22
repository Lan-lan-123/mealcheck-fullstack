import { test, expect } from './fixtures/authTest.js'

const first = { role: 'assistant', text: '午餐增加一份青菜', messageId: 101, deliveryStatus: 'COMPLETE' }
const second = { role: 'assistant', text: '晚餐搭配豆腐和米饭', messageId: 202, deliveryStatus: 'COMPLETE' }

async function fixture(page, prepend = false) {
  const plans = [], errors = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error' && message.text().includes('same key')) errors.push(message.text()) })
  await page.addInitScript(() => sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'plan-identity', role: 'USER' })))
  await page.route('**/api/**', route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = json => route.fulfill({ json })
    if (path === '/api/assistant/conversations') return send([{ id: 7, title: '第一段对话' }, { id: 8, title: '第二段对话' }])
    if (path === '/api/assistant/conversations/7') {
      const older = url.searchParams.has('before')
      return send({ id: 7, messages: [older ? second : first], hasMore: prepend && !older, nextBefore: prepend && !older ? 'older' : null })
    }
    if (path === '/api/assistant/conversations/8') return send({ id: 8, messages: [second], hasMore: false })
    if (path === '/api/plans' && req.method() === 'POST') {
      const plan = req.postDataJSON()
      plans.push(plan)
      return send({ id: plans.length, ...plan, status: 'OPEN', version: 0 })
    }
    return send(path === '/api/profile/goals/current' ? { goalType: 'balanced' }
      : path === '/api/assistant/proactive' ? { items: [] } : null)
  })
  await page.goto('/user/overview')
  await page.locator('.assistant-fab').click()
  await page.getByRole('button', { name: '第一段对话', exact: true }).click()
  await expect(page.locator('.assistant-message.assistant').first()).toContainText(first.text)
  return { plans, errors }
}

for (const mode of ['switch', 'prepend']) {
  test('plan text and source follow the displayed answer after ' + mode, async ({ page }) => {
    const state = await fixture(page, mode === 'prepend')
    if (mode === 'switch') await page.getByRole('button', { name: '第二段对话', exact: true }).click()
    else await page.getByRole('button', { name: '加载更早的消息', exact: true }).click()
    const answer = page.locator('.assistant-message.assistant').first()
    await expect(answer).toContainText(second.text)
    await answer.getByRole('button', { name: '加入计划', exact: true }).click()
    await expect(answer.getByLabel('具体行动')).toHaveValue(second.text)
    await answer.getByRole('button', { name: '保存行动计划', exact: true }).click()
    await expect.poll(() => state.plans.length).toBe(1)
    expect(state.plans[0]).toMatchObject({ actionText: second.text, sourceType: 'ASSISTANT', sourceMessageId: second.messageId })
    expect(state.errors).toEqual([])
  })
}

test('saving a plan in one conversation never marks another answer as saved', async ({ page }) => {
  const state = await fixture(page)
  let answer = page.locator('.assistant-message.assistant').first()
  await answer.getByRole('button', { name: '加入计划', exact: true }).click()
  await answer.getByRole('button', { name: '保存行动计划', exact: true }).click()
  await expect(answer.getByRole('button', { name: '已加入计划', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '第二段对话', exact: true }).click()
  answer = page.locator('.assistant-message.assistant').first()
  await expect(answer).toContainText(second.text)
  await expect(answer.getByRole('button', { name: '加入计划', exact: true })).toBeEnabled()
  await answer.getByRole('button', { name: '加入计划', exact: true }).click()
  await answer.getByRole('button', { name: '保存行动计划', exact: true }).click()
  await expect.poll(() => state.plans.length).toBe(2)
  expect(state.plans.map(plan => [plan.sourceMessageId, plan.actionText])).toEqual([[101, first.text], [202, second.text]])
  expect(state.errors).toEqual([])
})

test('prepending history preserves the existing answer draft with its source', async ({ page }) => {
  const state = await fixture(page, true)
  const original = page.locator('.assistant-message.assistant').filter({ has: page.getByText(first.text, { exact: true }) })
  await original.getByRole('button', { name: '加入计划', exact: true }).click()
  await original.getByLabel('具体行动').fill('我的午餐计划：增加青菜')
  await page.getByRole('button', { name: '加载更早的消息', exact: true }).click()
  await expect(page.locator('.assistant-message.assistant')).toHaveCount(2)
  await expect(original.getByLabel('具体行动')).toHaveValue('我的午餐计划：增加青菜')
  const older = page.locator('.assistant-message.assistant').first()
  await older.getByRole('button', { name: '加入计划', exact: true }).click()
  await expect(older.getByLabel('具体行动')).toHaveValue(second.text)
  await original.getByRole('button', { name: '保存行动计划', exact: true }).click()
  await expect.poll(() => state.plans.length).toBe(1)
  expect(state.plans[0]).toMatchObject({ actionText: '我的午餐计划：增加青菜', sourceMessageId: 101 })
  expect(state.errors).toEqual([])
})
