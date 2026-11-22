import { test, expect } from './fixtures/authTest.js'

test('conversational meal planning shows model tool calls, changed constraints and restored history', async ({ page }, testInfo) => {
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'planner', role: 'USER' }))
  })
  let count = 0
  const history = []
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = value => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) })
    if (path === '/api/assistant/conversations') return send(count ? [{ id: 7, title: '晚餐搭配' }] : [])
    if (path === '/api/assistant/conversations/7') return send({ id: 7, messages: history })
    if (path === '/api/assistant/stream') {
      const body = route.request().postDataJSON()
      count++
      if (count === 2) {
        expect(body.history.some(message => message.text.includes('预算25元'))).toBe(true)
        expect(body.question).toBe('改成外卖，预算还是25元')
      }
      const args = { mealType: 'DINNER', scene: count === 1 ? 'HOME' : 'TAKEOUT', budget: 25, availableMinutes: 30 }
      const answer = count === 1 ? '搭配库提供三套在家准备的方案，参考费用均在预算内。' : '已按外卖场景重新查询，时间为选餐准备参考，不含配送。'
      const toolCalls = [{ name: 'recommend_next_meal', arguments: JSON.stringify(args), origin: 'MODEL', status: 'SUCCESS', round: 1,
        durationMs: 18, resultSummary: count === 1 ? '找到 3 套搭配：米饭 + 鸡蛋 + 青菜' : '找到 3 套搭配：米饭 + 豆腐 + 青菜' }]
      const result = { answer, summary: answer, conversationId: 7, messageId: count + 10, deliveryStatus: 'COMPLETE', toolCalls }
      history.push({ role: 'user', text: body.question }, { role: 'assistant', text: answer, toolCalls })
      return route.fulfill({ contentType: 'text/event-stream', body: `event: meta\ndata: {"conversationId":7}\n\nevent: complete\ndata: ${JSON.stringify(result)}\n\n` })
    }
    return send(path === '/api/profile/goals/current' ? { goalType: 'balanced' }
      : path === '/api/assistant/proactive' ? { items: [] }
      : ['/api/meals', '/api/meals/summaries'].includes(path) ? { content: [], totalElements: 0, totalPages: 0, size: 10 }
      : null)
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/user/overview')
  await page.locator('.assistant-fab').click()
  const input = page.getByPlaceholder('问问怎么吃更合适')
  await input.fill('晚餐在家做，预算25元，30分钟内准备好，推荐下一餐搭配。')
  await page.getByRole('button', { name: '发送问题' }).click()
  const first = page.locator('.assistant-tool-calls').first()
  await first.locator(':scope > summary').click()
  await expect(first).toContainText('模型发起')
  await expect(first).toContainText('找到 3 套搭配')
  await first.getByText('查看工具名称与参数', { exact: true }).click()
  await expect(first.locator('pre')).toContainText('HOME')
  await input.fill('改成外卖，预算还是25元')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.assistant-tool-calls')).toHaveCount(2)
  const last = page.locator('.assistant-tool-calls').last()
  await last.locator(':scope > summary').click()
  await last.getByText('查看工具名称与参数', { exact: true }).click()
  await expect(last.locator('pre')).toContainText('TAKEOUT')
  await expect(last).toContainText('18 ms')
  await last.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('meal-planning-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await last.scrollIntoViewIfNeeded()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('meal-planning-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: '新对话', exact: true }).click()
  await page.getByRole('button', { name: '晚餐搭配', exact: true }).click()
  await expect(page.locator('.assistant-tool-calls')).toHaveCount(2)
  await expect(page.locator('.assistant-message.assistant').last()).toContainText('不含配送')
})
