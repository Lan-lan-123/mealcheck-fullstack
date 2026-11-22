import { test, expect } from './fixtures/authTest.js'

async function session(page, role) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(role => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'boundary-test', role }))
  }, role)
  return errors
}

const image = { name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFncAAAAASUVORK5CYII=', 'base64') }
const result = { recordId: 1, recognition: { foods: [{ name: '米饭', category: 'staple' }] }, evaluation: { score: 80, summary: '新分析结果', riskTags: [] }, advice: '搭配蔬菜' }

for (const change of ['image', 'goal', 'same', 'reload']) {
  test(`failed analysis ${change} uses a payload-specific key and preserves recovery`, async ({ page }) => {
    const errors = await session(page, 'USER')
    const keys = [], reads = []
    await page.route('**/api/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      if (!path.startsWith('/api/')) return route.continue()
      const send = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      if (path === '/api/meals/analysis-requests') {
        const key = request.headers()['idempotency-key']
        keys.push(key)
        if (keys.length === 1) return send({ key, status: 'FAILED' })
        if (change !== 'same' && key === keys[0]) return send({ message: '同一请求标识不能用于不同图片或目标' }, 409)
        return send({ key, status: 'SUCCEEDED', result })
      }
      if (path.startsWith('/api/meals/analysis-requests/')) {
        reads.push(path.split('/').at(-1))
        return send({ key: keys[0], status: 'FAILED' })
      }
      return send(path === '/api/profile/goals/current' ? { goalType: 'balanced' } : null)
    })
    await page.goto('/user/overview')
    await page.locator('input[type=file]').setInputFiles(image)
    await page.getByRole('button', { name: '开始分析', exact: true }).click()
    await expect(page.getByText('分析未完成，请使用原图重试。重复点击会复用原请求。', { exact: true })).toBeVisible()
    if (change === 'image') await page.locator('input[type=file]').setInputFiles({ ...image, name: 'new-meal.png', buffer: Buffer.concat([image.buffer, Buffer.from('different')]) })
    if (change === 'goal') await page.getByRole('combobox', { name: '本次分析目标' }).selectOption('fat_loss')
    if (change === 'reload') {
      await page.reload()
      await expect(page.getByRole('button', { name: '开始分析', exact: true })).toBeEnabled()
      await expect.poll(() => reads.length).toBe(1)
      await page.locator('input[type=file]').setInputFiles(image)
    }
    // Draft changes must not discard the earlier task's lookup before resubmission.
    await page.getByRole('button', { name: '查询上次分析结果', exact: true }).click()
    await expect.poll(() => reads.length).toBe(change === 'reload' ? 2 : 1)
    expect(reads.every(key => key === keys[0])).toBe(true)
    await expect(page.getByRole('button', { name: '开始分析', exact: true })).toBeEnabled()
    await page.getByRole('button', { name: '开始分析', exact: true }).click()
    await expect(page.locator('.summary')).toHaveText('新分析结果')
    expect(keys).toHaveLength(2)
    if (change === 'same') expect(keys[1]).toBe(keys[0])
    else expect(keys[1]).not.toBe(keys[0])
    expect(await page.evaluate(() => sessionStorage.getItem('mealcheck:analysis:boundary-test'))).toBeNull()
    expect(errors).toEqual([])
  })
}

async function bulkFixture(page, deletion) {
  const errors = await session(page, 'ADMIN')
  const reads = { meals: 0, analytics: 0 }, deleted = []
  let meals = [1, 2].map(id => ({ id, username: 'test-user', score: 80, foodNames: [], summary: `待删除餐食 ${id}`, createdAt: '2026-10-14T10:00:00' }))
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/admin/meals') {
      reads.meals++
      return send({ content: meals, totalElements: meals.length, totalPages: meals.length ? 1 : 0, page: 0, size: 20 })
    }
    if (path === '/api/admin/meals/analytics') { reads.analytics++; return send({ total: meals.length }) }
    if (request.method() === 'DELETE' && /^\/api\/admin\/meals\/\d+$/.test(path)) {
      const id = Number(path.split('/').at(-1))
      deleted.push(id)
      const failure = await deletion(id)
      if (failure) return send({ message: failure }, 500)
      meals = meals.filter(meal => meal.id !== id)
      return route.fulfill({ status: 204 })
    }
    return send({})
  })
  await page.goto('/admin/meals')
  await expect(page.getByText('待删除餐食 1', { exact: true })).toBeVisible()
  await expect(page.locator('.meal-analytics-grid').getByText('2', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '管理全体饮食记录', exact: true }).click()
  await page.getByRole('button', { name: '全选本页', exact: true }).click()
  return { reads, deleted, errors }
}

async function confirmBulkDelete(page, count = 2) {
  await page.getByRole('button', { name: `批量删除 (${count})`, exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认删除', exact: true }).click()
}

test('partial bulk deletion waits for every request, reloads data and retries only the failed row', async ({ page }) => {
  let release, failed = false, retry = false
  const gate = new Promise(resolve => { release = resolve })
  const state = await bulkFixture(page, async id => {
    if (retry) return null
    if (id === 1) { failed = true; return '模拟删除失败' }
    await gate
    return null
  })
  const before = { ...state.reads }
  await confirmBulkDelete(page)
  await expect.poll(() => failed).toBe(true)
  const bulk = page.getByRole('button', { name: '批量删除 (2)', exact: true })
  await expect(bulk).toBeDisabled()
  await expect(page.locator('tbody input[type=checkbox]').first()).toBeDisabled()
  await expect(page.getByRole('button', { name: '取消全选', exact: true })).toBeDisabled()
  // A direct click on a disabled control must not start another batch.
  await bulk.evaluate(button => button.click())
  expect(state.deleted).toEqual([1, 2])
  expect(state.reads).toEqual(before)
  release()
  await expect(page.getByRole('status')).toContainText('已删除 1 条饮食记录，1 条删除失败。')
  await expect(page.getByRole('alert')).toContainText('未删除记录：#1')
  await expect.poll(() => state.reads.meals).toBe(before.meals + 1)
  await expect.poll(() => state.reads.analytics).toBe(before.analytics + 1)
  await expect(page.getByText('待删除餐食 2', { exact: true })).toHaveCount(0)
  await expect(page.locator('tbody input[type=checkbox]')).toBeChecked()
  await expect(page.locator('.meal-analytics-grid').getByText('1', { exact: true })).toBeVisible()
  retry = true
  await confirmBulkDelete(page, 1)
  await expect(page.getByText('暂无饮食记录', { exact: true })).toBeVisible()
  expect(state.deleted).toEqual([1, 2, 1])
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(state.errors).toEqual([])
})

for (const outcome of ['success', 'failure']) {
  test(`bulk deletion ${outcome} refreshes data and reports accurate counts`, async ({ page }) => {
    const state = await bulkFixture(page, async () => outcome === 'failure' ? '暂时不可用' : null)
    const before = { ...state.reads }
    await confirmBulkDelete(page)
    await expect.poll(() => state.reads.meals).toBe(before.meals + 1)
    await expect.poll(() => state.reads.analytics).toBe(before.analytics + 1)
    if (outcome === 'success') {
      await expect(page.getByRole('status')).toContainText('已删除 2 条饮食记录')
      await expect(page.getByText('暂无饮食记录', { exact: true })).toBeVisible()
      await expect(page.getByRole('alert')).toHaveCount(0)
      await expect(page.getByRole('button', { name: '批量删除', exact: true })).toBeDisabled()
    } else {
      await expect(page.getByRole('status')).toContainText('已删除 0 条饮食记录，2 条删除失败。')
      await expect(page.getByRole('alert')).toContainText('#1、#2')
      await expect(page.locator('tbody input[type=checkbox]:checked')).toHaveCount(2)
      await expect(page.getByRole('button', { name: '批量删除 (2)', exact: true })).toBeEnabled()
    }
    expect(state.deleted).toEqual([1, 2])
    expect(state.errors).toEqual([])
  })
}
