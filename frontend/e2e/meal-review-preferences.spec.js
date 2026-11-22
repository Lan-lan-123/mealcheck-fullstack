import { test, expect } from './fixtures/authTest.js'

const image = { name: 'meal.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFncAAAAASUVORK5CYII=', 'base64') }
const foods = [{ name: '牛奶', category: 'dairy', confidence: .95, note: '可能是牛奶或豆浆', attributes: { fried: null, highOil: null } },
  { name: '红烧肉', category: 'protein', confidence: .99, attributes: { fried: false, highOil: null } },
  { name: '米饭', category: 'staple', confidence: .99 }]
const breakdown = { scoringVersion: 'structured-foods-v5', total: 67, items: [
  { rule: 'base', label: '基础分', points: 50, foods: [] }, { rule: 'structure', label: '结构加分', points: 35, foods: ['米饭', '红烧肉'] },
  { rule: 'risk', label: '高油风险', points: -18, foods: ['红烧肉'] }] }

async function setup(page, initialFoods = foods) {
  const state = { preferences: { style: 'OMNIVORE', avoidFoods: [], dislikedFoods: [], version: 0 }, writes: [], corrected: null, conflict: false, calls: 0 }, errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'tester', role: 'USER', displayName: '测试用户' }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/profile/diet-preferences') {
      if (request.method() === 'PUT') {
        state.writes.push(request.postDataJSON())
        if (state.conflict) { state.conflict = false; state.preferences.version++; return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: '偏好已修改' }) }) }
        state.preferences = { ...request.postDataJSON(), version: state.preferences.version + 1 }
      }
      return send(state.preferences)
    }
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/profile/ai-budget') return send({ enabled: false })
    if (path === '/api/profile/behavior-goals') return send({ content: [], totalElements: 0, totalPages: 0 })
    const result = { recordId: 1, revision: state.corrected ? 1 : 0, recognition: { foods: state.corrected?.foods.map(f => ({ ...f, confidence: 1, note: '用户确认' })) || initialFoods },
      evaluation: { score: state.corrected ? 67 : 90, summary: '搭配结构', riskTags: [], scoreBreakdown: state.corrected ? breakdown : null }, advice: '下一餐可轮换食材。',
      scoreChange: state.corrected ? { before: 90, after: 67, previousVersion: 'structured-foods-v5', currentVersion: 'structured-foods-v5' } : null }
    if (path === '/api/meals/analysis-requests') { state.calls++; return send({ key: request.headers()['idempotency-key'], status: 'SUCCEEDED', result }) }
    if (path === '/api/meals/1/corrections') { state.corrected = request.postDataJSON(); return send({ ...result, revision: 1,
      recognition: { foods: state.corrected.foods.map(f => ({ ...f, confidence: 1, note: '用户确认' })) }, evaluation: { ...result.evaluation, score: 67, scoreBreakdown: breakdown },
      scoreChange: { before: 90, after: 67, previousVersion: 'structured-foods-v5', currentVersion: 'structured-foods-v5' } }) }
    if (path.includes('/nutrition')) return send({ recordId: 1, enabled: false, status: 'DISABLED', items: [], estimatedFoods: 0, totals: null })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(null)
  })
  return { state, errors }
}
async function analyze(page) {
  await page.goto('/user/overview')
  await page.getByLabel('选择饮食图片').setInputFiles(image)
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await page.getByRole('button', { name: '核对并纠正识别', exact: true }).click()
}

test('high confidence ambiguity and unknown oil can be reviewed without another AI call, with score explanation', async ({ page }) => {
  const { state, errors } = await setup(page)
  await analyze(page)
  await page.getByRole('button', { name: '食物 1 这是什么食物？ 豆浆', exact: true }).click()
  await expect(page.getByLabel('食物名称 1', { exact: true })).toHaveValue('豆浆')
  await expect(page.getByLabel('食物分类 1', { exact: true })).toHaveValue('drink')
  await page.getByRole('button', { name: '食物 2 是否有明显高油风险？ 是', exact: true }).click()
  await page.getByLabel('修改原因').fill('核对饮品，确认高油风险')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(page.getByRole('dialog', { name: '纠正识别结果' })).toHaveCount(0)
  expect(state.corrected.foods[1].attributes).toEqual({ fried: false, highOil: true })
  await page.getByRole('button', { name: '查看完整分析与建议', exact: true }).click()
  const details = page.getByRole('region', { name: '评分明细' })
  await expect(details).toContainText('核对前 90 分 → 核对后 67 分')
  await details.getByText('查看评分明细', { exact: true }).click()
  await expect(details).toContainText('高油风险：-18 分')
  await expect(details).toContainText('合计 67 分')
  expect(state.calls).toBe(1)
  expect(errors).toEqual([])
})

test('uncertain identity is saved as an unknown drink and remains reviewable', async ({ page }) => {
  const { state } = await setup(page)
  await analyze(page)
  await page.getByRole('button', { name: '食物 1 这是什么食物？ 不确定', exact: true }).click()
  await page.getByLabel('确认食物 1 已核对').check()
  await page.getByLabel('确认食物 2 已核对').check()
  await page.getByLabel('修改原因').fill('无法确认白色饮品与用油')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(page.getByRole('dialog', { name: '纠正识别结果' })).toHaveCount(0)
  expect(state.corrected.foods[0]).toMatchObject({ name: '未确认饮品', category: 'drink', attributes: { fried: null, highOil: null } })
  await page.getByRole('button', { name: '核对并纠正识别', exact: true }).click()
  await expect(page.getByLabel('食物名称 1', { exact: true })).toHaveValue('未确认饮品')
  await expect(page.getByRole('group', { name: '快捷核对 食物 1' })).toBeVisible()
})

test('preferences save and reload on mobile, detect conflicts and can be cleared', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { state, errors } = await setup(page)
  await page.goto('/user/goals')
  await expect(page.getByLabel('饮食类型')).toBeEnabled()
  await page.getByLabel('饮食类型').selectOption('VEGAN')
  await page.getByLabel('忌口食材').fill('花生，花生、虾')
  await page.getByLabel('不喜欢的食材').fill('香菜')
  await page.getByRole('button', { name: '保存饮食偏好', exact: true }).click()
  await expect(page.getByText('饮食偏好已保存，将用于后续单餐建议、助手回复和新周报。')).toBeVisible()
  expect(state.writes[0]).toEqual({ style: 'VEGAN', avoidFoods: ['花生', '虾'], dislikedFoods: ['香菜'], version: 0 })
  await page.reload()
  await expect(page.getByLabel('饮食类型')).toHaveValue('VEGAN')
  await expect(page.getByLabel('忌口食材')).toHaveValue('花生、虾')
  state.conflict = true
  await page.getByRole('button', { name: '保存饮食偏好', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('偏好已在其他页面修改')
  await page.getByRole('button', { name: '重新加载偏好', exact: true }).click()
  await expect(page.getByLabel('饮食类型')).toBeEnabled()
  await page.getByRole('button', { name: '清空偏好', exact: true }).click()
  await page.getByRole('button', { name: '保存饮食偏好', exact: true }).click()
  await expect(page.getByText('饮食偏好已保存，将用于后续单餐建议、助手回复和新周报。')).toBeVisible()
  expect(state.preferences).toMatchObject({ style: 'OMNIVORE', avoidFoods: [], dislikedFoods: [], version: 3 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
  await page.screenshot({ path: 'test-results/diet-preferences-mobile.png', fullPage: true })
})

test('cooking conflicts and corn category can be reviewed while preserving independent oil risk', async ({ page }) => {
  const { state, errors } = await setup(page, [
    { name: '番茄炒蛋', category: 'protein', confidence: .99, note: '炒制', attributes: { fried: null, highOil: true },
      reviewIssues: [{ code: 'FRYING_CONFLICT', message: '油炸属性与做法线索冲突，已保留未知' }] },
    { name: '玉米', category: 'other', confidence: .99, note: '整根玉米', attributes: { fried: false, highOil: null },
      reviewIssues: [{ code: 'CATEGORY_CONFLICT', message: '项目将整根玉米归为主食，分类待核对' }] }
  ])
  await analyze(page)
  const dialog = page.getByRole('dialog', { name: '纠正识别结果' })
  await expect(dialog).toContainText('油炸属性与做法线索冲突')
  await expect(page.getByRole('button', { name: '保存并重新评分' })).toBeDisabled()
  await page.getByRole('button', { name: '食物 1 是否经过油炸？ 否', exact: true }).click()
  await page.getByRole('button', { name: '食物 2 按项目口径核对分类 主食', exact: true }).click()
  await expect(page.getByLabel('食物分类 2', { exact: true })).toHaveValue('staple')
  await page.getByLabel('修改原因').fill('核对炒制与玉米分类')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.corrected.foods[0].attributes).toEqual({ fried: false, highOil: true })
  expect(state.corrected.foods[1].category).toBe('staple')
  expect(state.calls).toBe(1)
  expect(errors).toEqual([])
})
