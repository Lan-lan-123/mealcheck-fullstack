import { test, expect } from './fixtures/authTest.js'

const username = 'storage-regression'
const storageKey = `mealcheck:analysis:${username}`
const image = { name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('fixture') }
const result = { recordId: 1, recognition: { foods: [{ name: '米饭', category: 'staple' }] },
  evaluation: { score: 80, summary: '存储异常下分析成功', riskTags: [] }, advice: '搭配蔬菜' }

async function fixture(page, { fault, savedKey = null, submit, load } = {}) {
  const errors = [], keys = [], reads = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(({ username, storageKey, fault, savedKey }) => {
    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username, role: 'USER' }))
    if (savedKey) sessionStorage.setItem(storageKey, savedKey)
    for (const operation of ['getItem', 'setItem', 'removeItem']) {
      const original = Storage.prototype[operation]
      Storage.prototype[operation] = function (key, ...args) {
        if (this === sessionStorage && key === storageKey && (
          fault === operation || fault === 'cleanup' && (operation === 'removeItem' || operation === 'setItem' && args[0] === '')
        )) throw new DOMException('Storage unavailable', operation === 'setItem' ? 'QuotaExceededError' : 'SecurityError')
        return original.call(this, key, ...args)
      }
    }
    if (fault === 'randomUUID') {
      const original = crypto.randomUUID.bind(crypto)
      let first = true
      crypto.randomUUID = () => {
        if (first) { first = false; throw new Error('请求标识生成失败，请重试。') }
        return original()
      }
    }
  }, { username, storageKey, fault, savedKey })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/meals/analysis-requests') {
      const key = route.request().headers()['idempotency-key']
      keys.push(key)
      return route.fulfill({ json: submit ? submit(key, keys.length) : { key, status: 'SUCCEEDED', result } })
    }
    if (path.startsWith('/api/meals/analysis-requests/')) {
      const key = path.split('/').at(-1)
      reads.push(key)
      return route.fulfill(load ? load(key) : { json: { key, status: 'SUCCEEDED', result } })
    }
    return route.fulfill({ json: path === '/api/profile/goals/current' ? { goalType: 'balanced' } : null })
  })
  await page.goto('/user/overview')
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  return { errors, keys, reads }
}

for (const fault of ['getItem', 'setItem']) {
  test(`unavailable ${fault} preserves recovery and retry identity in memory`, async ({ page }) => {
    const state = await fixture(page, {
      fault, submit: (key, attempt) => attempt === 1 ? { key, status: 'FAILED', errorCode: 'REMOTE_TIMEOUT' } : { key, status: 'SUCCEEDED', result },
      load: key => ({ json: { key, status: 'FAILED', errorCode: 'REMOTE_TIMEOUT' } })
    })
    await page.getByLabel('选择饮食图片').setInputFiles(image)
    const start = page.getByRole('button', { name: '开始分析', exact: true })
    await start.click()
    await expect(start).toBeEnabled()
    await expect(page.getByText('浏览器存储不可用，刷新后可能无法自动恢复分析，请保留当前页面。', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '查询上次分析结果', exact: true }).click()
    await expect.poll(() => state.reads).toEqual([state.keys[0]])
    await expect(start).toBeEnabled()
    await start.click()
    await expect(page.locator('.summary')).toHaveText(result.evaluation.summary)
    expect(state.keys).toHaveLength(2)
    expect(state.keys[1]).toBe(state.keys[0])
    await expect(page.getByRole('button', { name: '查询上次分析结果', exact: true })).toHaveCount(0)
    expect(state.errors).toEqual([])
  })
}

for (const fault of ['removeItem', 'cleanup']) {
  test(`failed ${fault} never turns a successful upload into a failed analysis`, async ({ page }) => {
    const state = await fixture(page, { fault })
    await page.getByLabel('选择饮食图片').setInputFiles(image)
    const start = page.getByRole('button', { name: '开始分析', exact: true })
    await start.click()
    await expect(page.locator('.summary')).toHaveText(result.evaluation.summary)
    await expect(start).toBeEnabled()
    await expect(page.getByRole('button', { name: '查询上次分析结果', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '另建一次分析', exact: true })).toHaveCount(0)
    if (fault === 'cleanup') {
      await expect(page.getByText(/浏览器未能清理上次分析状态/)).toBeVisible()
    } else {
      expect(await page.evaluate(key => sessionStorage.getItem(key), storageKey)).toBe('')
      await page.reload()
      await expect(start).toBeEnabled()
      expect(state.reads).toEqual([])
    }
    expect(state.errors).toEqual([])
  })
}

test('a recovered success remains successful when clearing persistence fails', async ({ page }) => {
  const state = await fixture(page, { fault: 'cleanup', savedKey: 'saved-analysis-key' })
  await expect(page.locator('.summary')).toHaveText(result.evaluation.summary)
  await expect(page.getByText('已找回上次图片分析结果。', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始分析', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '查询上次分析结果', exact: true })).toHaveCount(0)
  expect(state.keys).toEqual([])
  expect(state.reads).toEqual(['saved-analysis-key'])
  expect(state.errors).toEqual([])
})

test('an absent recovered task clears in-memory recovery even if persistence cleanup fails', async ({ page }) => {
  const state = await fixture(page, { fault: 'cleanup', savedKey: 'missing-analysis-key',
    load: () => ({ status: 404, json: { message: '任务不存在' } }) })
  await expect(page.getByRole('button', { name: '开始分析', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '查询上次分析结果', exact: true })).toHaveCount(0)
  await page.getByLabel('选择饮食图片').setInputFiles(image)
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await expect(page.locator('.summary')).toHaveText(result.evaluation.summary)
  expect(state.keys).toHaveLength(1)
  expect(state.errors).toEqual([])
})

test('a request preparation error releases upload state and allows another attempt', async ({ page }) => {
  const state = await fixture(page, { fault: 'randomUUID' })
  await page.getByLabel('选择饮食图片').setInputFiles(image)
  const start = page.getByRole('button', { name: '开始分析', exact: true })
  await start.click()
  await expect(page.getByText('请求标识生成失败，请重试。', { exact: true })).toBeVisible()
  await expect(start).toBeEnabled()
  expect(state.keys).toEqual([])
  await start.click()
  await expect(page.locator('.summary')).toHaveText(result.evaluation.summary)
  expect(state.keys).toHaveLength(1)
  expect(state.errors).toEqual([])
})
