import { test, expect } from '@playwright/test'

async function setup(page, olderHandler) {
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'history-test-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'demo', role: 'USER' }))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const send = value => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) })
    if (url.pathname === '/api/assistant/conversations') return send([{ id: 7, title: '测试长会话' }])
    if (url.pathname === '/api/assistant/conversations/7') {
      expect(url.searchParams.get('limit')).toBe('40')
      if (url.searchParams.has('before')) {
        expect(url.searchParams.get('before')).toBe('history-cursor')
        return olderHandler(route, send)
      }
      return send({ id: 7, messages: [{ role: 'user', text: '最近的问题' }, { role: 'assistant', text: '最近的回答' }], hasMore: true, nextBefore: 'history-cursor' })
    }
    return send(url.pathname === '/api/profile/goals/current' ? { goalType: 'balanced' } : {})
  })
  await page.goto('/')
  await page.locator('.assistant-fab').click()
  await page.getByRole('button', { name: '测试长会话', exact: true }).click()
  await expect(page.getByText('最近的回答', { exact: true })).toBeVisible()
}

test('loads older history before current messages and stops at the oldest page', async ({ page }) => {
  await setup(page, (route, send) => send({ id: 7, messages: [{ role: 'user', text: '最早的问题' }, { role: 'assistant', text: '最早的回答' }], hasMore: false, nextBefore: null }))
  await page.getByRole('button', { name: '加载更早的消息' }).click()
  await expect(page.locator('.assistant-message')).toHaveText(['最早的问题', '最早的回答', '最近的问题', '最近的回答'])
  await expect(page.getByRole('button', { name: '加载更早的消息' })).toHaveCount(0)
})

test('switching to a new conversation cancels and ignores an older-history response', async ({ page }) => {
  let release, entered
  const gate = new Promise(resolve => { release = resolve })
  const started = new Promise(resolve => { entered = resolve })
  await setup(page, async (route, send) => {
    entered()
    await gate
    await send({ id: 7, messages: [{ role: 'user', text: '过期历史消息' }], hasMore: false }).catch(() => {})
  })
  await page.getByRole('button', { name: '加载更早的消息' }).click()
  await started
  await page.getByRole('button', { name: '新对话', exact: true }).click()
  release()
  await expect(page.getByText('过期历史消息', { exact: true })).toHaveCount(0)
  await expect(page.locator('.assistant-message')).toHaveCount(1)
  await expect(page.getByPlaceholder('问问怎么吃更合适')).toBeEnabled()
  await page.getByRole('button', { name: '测试长会话', exact: true }).click()
  await expect(page.getByText('最近的回答', { exact: true })).toBeVisible()
})
