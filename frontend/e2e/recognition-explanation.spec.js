import { test, expect } from './fixtures/authTest.js'

const image = { name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFncAAAAASUVORK5CYII=', 'base64') }
const foods = [{ name: '米饭', category: 'staple', confidence: .95 }, { name: '青菜', category: 'vegetable', confidence: .65, note: '菜叶部分遮挡' }]
const uncertainFoods = [{ name: '鸡肉', category: 'protein', confidence: .3, note: '被餐具遮挡' }]
const analysis = { recordId: 1, revision: 0, recognition: { foods, uncertainFoods }, evaluation: { score: 80, summary: '搭配建议', riskTags: [] }, advice: '核对后补充' }

async function setup(page, code) {
  const state = { keys: [], corrected: null }, errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'tester', role: 'USER', displayName: '测试用户' }))
  })
  await page.route('**/api/**', route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/profile/ai-budget') return send({ enabled: false })
    if (path === '/api/meals/analysis-requests') {
      const key = request.headers()['idempotency-key']; state.keys.push(key)
      return send(code && state.keys.length === 1 ? { key, status: 'FAILED', errorCode: code } : { key, status: 'SUCCEEDED', result: analysis })
    }
    if (path.startsWith('/api/meals/analysis-requests/')) return send({ key: path.split('/').pop(), status: 'FAILED', errorCode: code })
    if (path === '/api/meals/1/corrections') {
      state.corrected = request.postDataJSON()
      return send({ ...analysis, revision: 1, recognition: { foods: state.corrected.foods.map(food => ({ ...food, confidence: 1, note: '用户确认' })), uncertainFoods: [] } })
    }
    if (path === '/api/meals/1') return send({ id: 1, revision: 0, createdAt: '2026-10-14T04:00:00', foods, uncertainFoods, score: 80 })
    if (path === '/api/meals/summaries') return send({ content: [{ id: 1, createdAt: '2026-10-14T04:00:00', foodNames: ['米饭', '青菜'], score: 80 }], totalElements: 1, totalPages: 1 })
    if (path === '/api/meals/1/nutrition') return send({ recordId: 1, recordRevision: 0, version: 0, enabled: false, status: 'DISABLED', items: [], estimatedFoods: 0, totals: null })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(null)
  })
  await page.goto('/user/overview')
  return { state, errors }
}
async function upload(page) {
  await page.getByLabel('选择饮食图片').setInputFiles(image)
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
}

test('uncertain foods are shown separately, require explicit inclusion and disappear after correction', async ({ page }) => {
  const { state, errors } = await setup(page)
  await upload(page)
  const review = page.getByRole('region', { name: '识别结果待核对' })
  await expect(review).toContainText('青菜 · 蔬菜 · 置信度 65%')
  await expect(review).toContainText('鸡肉 · 蛋白质 · 置信度 30% · 未计入当前评分')
  await expect(review).toContainText('被餐具遮挡')
  await page.getByRole('button', { name: '核对并纠正识别' }).click()
  await expect(page.getByRole('textbox', { name: '食物名称 3' })).toHaveCount(0)
  await page.getByRole('button', { name: '确认并添加：鸡肉' }).click()
  await expect(page.getByRole('textbox', { name: '食物名称 3' })).toHaveValue('鸡肉')
  await expect(page.getByRole('button', { name: '保存并重新评分' })).toBeDisabled()
  await page.getByLabel('确认食物 2 已核对').check()
  await page.getByLabel('食物 1 油炸做法', { exact: true }).selectOption('false')
  await page.getByLabel('食物 1 高油风险', { exact: true }).selectOption('unknown')
  await page.getByRole('textbox', { name: '修改原因' }).fill('确认食物名称与分类')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(review).toHaveCount(0)
  expect(state.corrected.foods).toHaveLength(3)
  expect(state.corrected.foods[0].attributes).toEqual({ fried: false, highOil: null })
  await page.getByRole('button', { name: '纠正识别结果', exact: true }).click()
  await expect(page.getByLabel('食物 1 油炸做法', { exact: true })).toHaveValue('false')
  await expect(page.getByLabel('食物 1 高油风险', { exact: true })).toHaveValue('unknown')
  expect(errors).toEqual([])
})

for (const [code, title] of [['NON_FOOD', '未识别到餐食'], ['IMAGE_UNCLEAR', '图片不清晰或细节不足']]) {
  test(`${code} prompts replacement, keeps its reason after reload and uses a new key for the new image`, async ({ page }) => {
    const { state, errors } = await setup(page, code)
    await upload(page)
    const progress = page.getByRole('region', { name: '餐食分析进度' })
    await expect(progress.getByText(title, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '稍后用原图重试' })).toHaveCount(0)
    await page.reload()
    await expect(progress.getByText(title, { exact: true })).toBeVisible()
    const chooser = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: '选择另一张餐食照片' }).click()
    await (await chooser).setFiles({ ...image, name: 'clear-meal.png' })
    await page.getByRole('button', { name: '开始分析', exact: true }).click()
    await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible()
    expect(state.keys[1]).not.toBe(state.keys[0])
    expect(errors).toEqual([])
  })
}
test('model service error retries the same file with the same request key and does not advise replacement', async ({ page }) => {
  const { state, errors } = await setup(page, 'MODEL_SERVICE_ERROR')
  await upload(page)
  await expect(page.getByText('模型服务暂时异常', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '选择另一张餐食照片' })).toHaveCount(0)
  await page.getByRole('button', { name: '稍后用原图重试' }).click()
  await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible()
  expect(state.keys[1]).toBe(state.keys[0])
  expect(errors).toEqual([])
})
test('historical record details retain confidence explanations on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { errors } = await setup(page)
  await page.goto('/user/records')
  await page.getByRole('button', { name: '查看餐食详情' }).click()
  const drawer = page.getByRole('dialog', { name: '餐食记录详情' })
  const review = drawer.getByRole('region', { name: '识别结果待核对' })
  await expect(review).toContainText('置信度 65%')
  const bounds = await review.boundingBox()
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390)
  await drawer.getByRole('button', { name: '核对并纠正识别' }).click()
  await expect(drawer).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: '纠正识别结果' })).toBeVisible()
  expect(errors).toEqual([])
})

for (const [code, title] of [
  ['REMOTE_TIMEOUT', '模型响应超时'], ['MODEL_RESPONSE_INVALID', '识别结果格式无效'], ['RECOGNITION_INCONSISTENT', '识别结果不一致']
]) {
  test(`${code} keeps the original file and request identity for manual retry`, async ({ page }) => {
    const { state, errors } = await setup(page, code)
    await upload(page)
    const progress = page.getByRole('region', { name: '餐食分析进度' })
    await expect(progress.getByText(title, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '选择另一张餐食照片' })).toHaveCount(0)
    expect(state.keys).toHaveLength(1)
    await page.getByRole('button', { name: '稍后用原图重试' }).click()
    await expect(page.getByRole('button', { name: '纠正识别结果', exact: true })).toBeVisible()
    expect(state.keys).toHaveLength(2)
    expect(state.keys[1]).toBe(state.keys[0])
    expect(errors).toEqual([])
  })
}

for (const [code, title] of [
  ['AI_BUDGET_EXCEEDED', 'AI 额度暂时用尽'], ['MODEL_REQUEST_LIMITED', '模型服务请求受限'], ['REMOTE_CONFIGURATION', '模型连接配置需要检查']
]) {
  test(`${code} explains the wait or configuration requirement and persists it after refresh`, async ({ page }) => {
    const { state, errors } = await setup(page, code)
    await upload(page)
    const progress = page.getByRole('region', { name: '餐食分析进度' })
    await expect(progress.getByText(title, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '稍后用原图重试' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '选择另一张餐食照片' })).toHaveCount(0)
    await page.reload()
    await expect(progress.getByText(title, { exact: true })).toBeVisible()
    expect(state.keys).toHaveLength(1)
    expect(errors).toEqual([])
  })
}

test('invalid-result guidance and retry action fit a narrow screen', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { errors } = await setup(page, 'MODEL_RESPONSE_INVALID')
  await upload(page)
  const progress = page.getByRole('region', { name: '餐食分析进度' })
  await expect(progress.getByText('识别结果格式无效', { exact: true })).toBeVisible()
  await expect(progress).toContainText('这不表示照片不清晰或没有食物')
  const retry = page.getByRole('button', { name: '稍后用原图重试' })
  await expect(retry).toBeEnabled()
  for (const element of [progress, retry]) {
    const bounds = await element.boundingBox()
    expect(bounds.x).toBeGreaterThanOrEqual(0)
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(390)
  }
  await progress.screenshot({ path: testInfo.outputPath('invalid-result-mobile.png') })
  expect(errors).toEqual([])
})
