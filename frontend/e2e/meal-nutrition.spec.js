import { test, expect } from '@playwright/test'

async function fixture(page, { conflict = false, foods = [{ name: '示例食物', category: 'other' }] } = {}) {
  let record = { id: 1, revision: 0, foods, score: 80,
    summary: '测试餐食', createdAt: '2026-10-10T12:00:00', categoryCounts: {}, riskTags: [], advice: '结构建议' }
  let snapshot = null, writes = 0
  const empty = (enabled = false, version = 0, status = 'DISABLED') => ({ recordId: 1, recordRevision: record.revision,
    version, enabled, status, totals: null, estimatedFoods: 0,
    items: record.foods.map((food, foodIndex) => ({ ...food, foodIndex, grams: null, per100g: null, source: null })) })
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'fixture-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'alice', role: 'USER', displayName: '测试用户' }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    if (path === '/api/meals/1/nutrition') {
      if (request.method() === 'GET') return send(snapshot?.enabled && snapshot.recordRevision !== record.revision
        ? empty(true, snapshot.version, 'STALE') : snapshot || empty())
      writes++
      const payload = request.postDataJSON()
      if (conflict) return send({ message: '冲突' }, 409)
      expect(payload.expectedRevision).toBe(record.revision)
      expect(payload.expectedVersion).toBe(snapshot?.version || 0)
      if (!payload.enabled) snapshot = empty(false, payload.expectedVersion + 1)
      else {
        const items = payload.items.map(item => ({ ...record.foods[item.foodIndex], ...item }))
        const known = items.every(item => item.grams !== null && Object.values(item.per100g).every(value => value !== null))
        snapshot = { ...empty(true, payload.expectedVersion + 1, known ? 'ESTIMATED' : 'INCOMPLETE'), items,
          totals: known ? { energyKcal: 300, proteinGrams: 15, fatGrams: 7.5, carbohydrateGrams: 30 } : null }
      }
      return send(snapshot)
    }
    if (path === '/api/meals/1/corrections') {
      record = { ...record, foods: request.postDataJSON().foods, revision: record.revision + 1 }
      return send({ recordId: 1, revision: record.revision, recognition: { foods: record.foods }, evaluation: { score: 80 }, advice: '结构建议' })
    }
    if (path === '/api/meals') return send({ content: [record], totalElements: 1, totalPages: 1, page: 0 })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    if (path === '/api/profile/ai-budget') return send({ enabled: false })
    if (path === '/api/auth/captcha') return send({ captchaId: 'test', question: '1 + 2 = ?' })
    if (path === '/api/meals/analysis-requests') return send({ key: request.headers()['idempotency-key'], status: 'SUCCEEDED',
      result: { recordId: 1, revision: 0, recognition: { foods: record.foods }, evaluation: { score: 80 }, advice: '结构建议' } })
    return send(null)
  })
  await page.goto('/user/records')
  await openDetails(page)
  return { writes: () => writes }
}

async function openDetails(page) {
  await page.getByRole('button', { name: '查看餐食详情', exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '开启本餐可选份量与估算' })).toBeVisible()
}

async function fillComplete(page) {
  await page.getByRole('checkbox').check()
  await page.getByRole('spinbutton', { name: '示例食物 份量（克）' }).fill('150')
  await page.getByText('填写每 100 克营养数据（可选）', { exact: true }).click()
  for (const [label, value] of [['能量（kcal / 100g）', '200'], ['蛋白质（g / 100g）', '10'], ['脂肪（g / 100g）', '5'], ['碳水化合物（g / 100g）', '20']]) {
    await page.getByRole('spinbutton', { name: `示例食物 ${label}`, exact: true }).fill(value)
  }
  await page.getByRole('textbox', { name: '示例食物 数据来源' }).fill('测试包装标签')
  await page.getByRole('button', { name: '保存份量与粗略估算' }).click()
  await expect(page.locator('.nutrition-estimate')).toContainText('300 kcal')
}

test('optional feature is disabled by default and portions alone never fabricate a total', async ({ page }) => {
  const state = await fixture(page)
  await expect(page.getByRole('checkbox')).not.toBeChecked()
  await expect(page.locator('.nutrition-warning')).toContainText('营养估算不准确')
  await expect(page.locator('.nutrition-estimate')).toHaveCount(0)
  expect(state.writes()).toBe(0)
  await page.getByRole('checkbox').check()
  await page.getByRole('spinbutton', { name: '示例食物 份量（克）' }).fill('150')
  await page.getByRole('button', { name: '保存份量与粗略估算' }).click()
  await expect(page.getByText('无法估算整餐：', { exact: false })).toBeVisible()
  await expect(page.getByText('已具备完整估算资料：0 / 1 项食物。')).toBeVisible()
  await expect(page.locator('.nutrition-estimate')).toHaveCount(0)
  await page.getByRole('button', { name: '关闭详情' }).click()
  await openDetails(page)
  await expect(page.getByRole('spinbutton', { name: '示例食物 份量（克）' })).toHaveValue('150')
})

test('a meal without food items explains why optional estimation cannot be enabled', async ({ page }) => {
  const state = await fixture(page, { foods: [] })
  await expect(page.getByRole('checkbox', { name: '开启本餐可选份量与估算' })).toBeDisabled()
  await expect(page.getByText('当前餐食没有可估算的食物项，请先纠正识别结果。')).toBeVisible()
  expect(state.writes()).toBe(0)
})

test('complete input persists and disabling clears all estimates and portions', async ({ page }) => {
  const state = await fixture(page)
  await fillComplete(page)
  await expect(page.locator('.nutrition-estimate')).toContainText('仅供参考')
  await page.getByRole('button', { name: '关闭详情' }).click()
  await openDetails(page)
  await expect(page.getByRole('checkbox')).toBeChecked()
  await expect(page.locator('.nutrition-estimate')).toContainText('300 kcal')
  await page.getByRole('checkbox').uncheck()
  await expect(page.locator('.nutrition-estimate')).toHaveCount(0)
  await page.getByRole('button', { name: '保存关闭设置' }).click()
  await page.getByRole('button', { name: '关闭详情' }).click()
  await openDetails(page)
  await expect(page.getByRole('checkbox')).not.toBeChecked()
  await page.getByRole('checkbox').check()
  await expect(page.getByRole('spinbutton', { name: '示例食物 份量（克）' })).toHaveValue('')
  expect(state.writes()).toBe(2)
})

test('food correction invalidates old portions and nutrient estimates', async ({ page }) => {
  await fixture(page)
  await fillComplete(page)
  await page.getByRole('button', { name: '关闭详情' }).click()
  await page.getByRole('button', { name: '纠正识别', exact: true }).click()
  await page.getByRole('textbox', { name: '食物名称 1' }).fill('纠正后的食物')
  await page.getByRole('textbox', { name: '修改原因' }).fill('食物名称错误')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(page.getByRole('dialog', { name: '纠正识别结果' })).toHaveCount(0)
  await openDetails(page)
  await expect(page.getByText('食物识别已修改，旧估算已失效，请重新填写份量和数据。')).toBeVisible()
  await expect(page.getByRole('spinbutton', { name: '纠正后的食物 份量（克）' })).toHaveValue('')
  await expect(page.locator('.nutrition-estimate')).toHaveCount(0)
})

test('concurrent edit conflict gives a reload action without displaying a fabricated result', async ({ page }) => {
  await fixture(page, { conflict: true })
  await page.getByRole('checkbox').check()
  await page.getByRole('spinbutton', { name: '示例食物 份量（克）' }).fill('100')
  await page.getByRole('button', { name: '保存份量与粗略估算' }).click()
  await expect(page.getByRole('alert')).toContainText('餐食或份量已被修改')
  await page.getByRole('button', { name: '重新加载设置' }).click()
  await expect(page.getByRole('checkbox')).not.toBeChecked()
  await expect(page.locator('.nutrition-estimate')).toHaveCount(0)
})

test('mobile optional form fits the detail drawer and the analysis entry opens it', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await fixture(page)
  await fillComplete(page)
  const panel = page.getByRole('region', { name: '可选份量与营养估算' })
  const bounds = await panel.boundingBox()
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390)
  await page.screenshot({ path: 'test-results/nutrition-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '关闭详情' }).click()
  await page.getByRole('button', { name: '打开导航菜单' }).click()
  await page.getByRole('button', { name: '饮食分析', exact: true }).click()
  await page.locator('input[type=file]').setInputFiles({ name: 'meal.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('fixture') })
  await page.getByRole('button', { name: '开始分析', exact: true }).click()
  await page.getByRole('button', { name: '可选份量与营养估算', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '完整餐食分析' })).toBeVisible()
  await expect(page.locator('.nutrition-estimate')).toContainText('300 kcal')
})
