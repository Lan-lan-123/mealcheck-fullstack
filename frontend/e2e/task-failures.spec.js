import { test, expect } from '@playwright/test'

const failure = { id: 1, taskType: 'MEAL_ANALYSIS', userId: 9, taskKey: 'meal-request-001', eventId: 'old-event',
  phase: 'ANALYZE', errorCode: 'REMOTE_TIMEOUT', errorSummary: '远程调用超时', status: 'OPEN', attempts: 1,
  deadLettered: true, version: 0, failedAt: '2026-10-02T10:00:00', updatedAt: '2026-10-02T10:00:00',
  taskStatus: 'FAILED', manualRetryCount: 0, retryable: true, retryBlockedReason: '', handlingNote: '' }
const detail = item => ({ failure: item, history: [item], operations: [] })
async function setup(page, handler) {
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'test-admin-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (await handler(route, url, send)) return
    if (url.pathname.endsWith('/summary')) return send({ open: 1, retrying: 0, oldestOpenSeconds: 120 })
    if (url.pathname === '/api/admin/task-failures') return send({ content: [failure], totalElements: 1, totalPages: 1 })
    if (url.pathname === '/api/admin/task-failures/1') return send(detail(failure))
    return send(null)
  })
  await page.goto('/admin/task-failures')
}

test('retry requires a reason, uses one operation identity and follows recovery results', async ({ page }) => {
  let actions = 0, current = failure
  await setup(page, async (route, url, send) => {
    if (url.pathname.endsWith('/1/retry')) {
      actions++
      expect(route.request().postDataJSON()).toMatchObject({ expectedVersion: 0, reason: '依赖已经恢复' })
      expect(route.request().postDataJSON().operationId).toMatch(/^[0-9a-f-]{36}$/)
      current = { ...failure, status: 'RETRYING', taskStatus: 'PENDING', version: 1, retryable: false, manualRetryCount: 1, retryBlockedReason: '已发起重试' }
      await send(detail(current)); return true
    }
    if (url.pathname === '/api/admin/task-failures/1') { await send(detail(current)); return true }
    return false
  })
  await page.getByRole('button', { name: '查看并处理' }).click()
  const dialog = page.getByRole('dialog', { name: '失败任务详情' })
  await expect(dialog.getByRole('button', { name: '重新执行任务' })).toBeDisabled()
  await expect(dialog.getByText(/可能再次调用模型并产生费用/)).toBeVisible()
  await dialog.getByRole('textbox', { name: '处理原因' }).fill('依赖已经恢复')
  await dialog.getByRole('button', { name: '重新执行任务' }).dblclick()
  await expect(dialog.getByText(/任务已重新入队/)).toBeVisible()
  expect(actions).toBe(1)
  current = { ...current, status: 'RECOVERED', taskStatus: 'SUCCEEDED', version: 2 }
  await dialog.getByRole('button', { name: '刷新任务结果' }).click()
  await expect(dialog.getByText('SUCCEEDED', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '重新执行任务' })).toHaveCount(0)
})

test('a lost response reuses the same operation ID when the administrator retries', async ({ page }) => {
  const payloads = []
  await setup(page, async (route, url, send) => {
    if (!url.pathname.endsWith('/retry')) return false
    payloads.push(route.request().postDataJSON())
    if (payloads.length === 1) await route.abort('failed')
    else await send(detail({ ...failure, status: 'RETRYING', version: 1, retryable: false }))
    return true
  })
  await page.getByRole('button', { name: '查看并处理' }).click()
  const dialog = page.getByRole('dialog', { name: '失败任务详情' })
  await dialog.getByRole('textbox', { name: '处理原因' }).fill('确认依赖恢复')
  await dialog.getByRole('button', { name: '重新执行任务' }).click()
  await expect(dialog.getByRole('alert')).toBeVisible()
  await dialog.getByRole('button', { name: '重新执行任务' }).click()
  await expect(dialog.getByText(/任务已重新入队/)).toBeVisible()
  expect(payloads).toHaveLength(2); expect(payloads[1]).toEqual(payloads[0])
})

test('missing image blocks retry and allows audited closure on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const blocked = { ...failure, retryable: false, retryBlockedReason: '原图已清理，需要用户重新上传' }
  await setup(page, async (route, url, send) => {
    if (url.pathname.endsWith('/1/close')) {
      expect(route.request().postDataJSON().reason).toBe('已通知用户重新上传')
      await send(detail({ ...blocked, status: 'CLOSED', version: 1 })); return true
    }
    if (url.pathname === '/api/admin/task-failures/1') { await send(detail(blocked)); return true }
    return false
  })
  await page.getByRole('button', { name: '查看并处理' }).click()
  const dialog = page.getByRole('dialog', { name: '失败任务详情' })
  await expect(dialog.getByText(/原图已清理/)).toBeVisible()
  await dialog.getByRole('textbox', { name: '处理原因' }).fill('已通知用户重新上传')
  await expect(dialog.getByRole('button', { name: '重新执行任务' })).toBeDisabled()
  await dialog.getByRole('button', { name: '关闭失败事件' }).click()
  await expect(dialog.getByText(/失败事件已关闭/)).toBeVisible()
  await page.screenshot({ path: 'test-results/task-failure-mobile.png', fullPage: true })
})

test('late filter responses cannot overwrite the current failure list', async ({ page }) => {
  let entered, release
  const started = new Promise(resolve => { entered = resolve }), gate = new Promise(resolve => { release = resolve })
  await setup(page, async (route, url, send) => {
    if (url.pathname !== '/api/admin/task-failures') return false
    const code = url.searchParams.get('code')
    if (code === 'OLD') { entered(); await gate }
    await send({ content: [{ ...failure, errorSummary: code ? `${code} 错误` : '远程调用超时' }], totalElements: 1, totalPages: 1 }).catch(() => {})
    return true
  })
  await page.getByRole('textbox', { name: '错误代码' }).fill('OLD')
  await page.getByRole('button', { name: '筛选', exact: true }).click(); await started
  await page.getByRole('textbox', { name: '错误代码' }).fill('NEW')
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  await expect(page.getByRole('cell', { name: /NEW 错误/ })).toBeVisible()
  release(); await expect(page.getByText('OLD 错误')).toHaveCount(0)
})
