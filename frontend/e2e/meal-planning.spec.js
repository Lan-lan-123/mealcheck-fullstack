import { test, expect } from './fixtures/authTest.js'

const record = { id: 1, createdAt: '2026-10-18T00:00:00', occurredAt: '2026-10-18T00:00:00', mealType: 'BREAKFAST', score: 80, revision: 0,
  foods: [{ name: '牛奶', category: 'dairy', confidence: .7, note: '可能是牛奶或豆浆' }], summary: '待核对', advice: '留意搭配' }
const plan = { id: 11, title: '午餐搭配蔬菜', actionText: '选一份蔬菜', plannedDate: '2026-10-18', sourceType: 'MANUAL', status: 'OPEN', version: 0 }
async function setup(page) {
  const state = { date: '2026-10-18', review: true, plans: [{ ...plan }], nextRequests: [], planWrites: [], failToday: false, failNext: false, empty: false, delay: null, todayReads: 0 }
  const errors = []
  page.on('pageerror', err => errors.push(err.message))
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'planner', displayName: '计划测试', role: 'USER' }))
  })
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = value => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) })
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced' })
    if (path === '/api/profile/ai-budget') return send({ enabled: false })
    if (path === '/api/meals/today') {
      state.todayReads++
      if (state.failToday) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '今日数据暂不可用' }) })
      const pending = state.plans.filter(p => p.status === 'OPEN' && p.plannedDate === state.date)
      return send({ date: state.date, businessZone: 'Asia/Shanghai', totalMeals: 1,
        slots: { BREAKFAST: 'RECORDED', LUNCH: 'SKIPPED', DINNER: 'UNKNOWN', SNACK: 'UNKNOWN' },
        reviewFoodCount: state.review ? 1 : 0, reviewMealCount: state.review ? 1 : 0,
        reviews: state.review ? [{ recordId: 1, mealType: 'BREAKFAST', foods: ['牛奶'], foodCount: 1 }] : [],
        pendingPlanCount: pending.length, pendingPlans: pending })
    }
    if (path === '/api/meals/1') return send(record)
    if (path === '/api/meals/1/corrections') {
      state.review = false
      return send({ recordId: 1, revision: 1, recognition: { foods: [{ name: '豆浆', category: 'drink', confidence: 1, note: '用户确认' }] }, evaluation: { score: 80 }, advice: '留意搭配' })
    }
    if (path === '/api/meals/next-meal') {
      const body = req.postDataJSON(); state.nextRequests.push(body)
      if (state.delay) await state.delay
      if (state.failNext) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '搭配暂不可用' }) })
      const foods = [['米饭', '豆腐', '青菜'], ['玉米', '鸡蛋', '番茄'], ['红薯', '鸡肉', '黄瓜']]
      return send({ date: state.date, constraints: body, goal: 'balanced', preferenceSummary: '已应用饮食偏好', recentMealCount: 1,
        context: '参考最近 1 条已记录餐食', costBasis: '规划估计，不是门店报价，不含配送费用。', timeBasis: '选餐约 5 分钟，不含排队和配送。', message: state.empty ? '当前搭配库没有同时满足这些条件的方案。' : '选择一套搭配',
        options: state.empty ? [] : foods.map((items, index) => ({ id: `option-${index}`, title: items.join(' + '), foods: items,
          costMin: 8 + index, costMax: 10 + index, readyMinutes: 5, reasons: ['根据当前目标搭配', '已筛选忌口食材'], actionText: items.join('、') + '，费用为参考。' })) })
    }
    if (path === '/api/plans' && req.method() === 'POST') {
      const body = req.postDataJSON(); state.planWrites.push(body)
      const saved = { ...body, id: 12 + state.planWrites.length, status: 'OPEN', version: 0 }
      state.plans.push(saved); return send(saved)
    }
    if (/^\/api\/plans\/\d+$/.test(path) && req.method() === 'PUT') {
      const body = req.postDataJSON(); const item = state.plans.find(p => p.id === Number(path.split('/').at(-1)))
      Object.assign(item, body, { version: item.version + 1 }); return send(item)
    }
    if (path === '/api/plans') return send({ content: state.plans, totalElements: state.plans.length, totalPages: 1 })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(null)
  })
  return { state, errors }
}
const today = page => page.getByRole('region', { name: '今日饮食总览' })
const next = page => page.getByRole('region', { name: '下一餐搭配推荐' })

test('today preserves meal states, opens correction and refreshes after saving', async ({ page }) => {
  const { errors } = await setup(page)
  await page.goto('/user/overview')
  await expect(today(page)).toContainText('已确认未吃')
  await expect(today(page)).toContainText('未记录，情况未知')
  await today(page).getByRole('button', { name: '核对这餐' }).click()
  await expect(page.getByRole('dialog', { name: '纠正识别结果' })).toBeVisible()
  await page.getByRole('button', { name: '食物 1 这是什么食物？ 豆浆', exact: true }).click()
  await page.getByLabel('修改原因').fill('确认是豆浆')
  await page.getByRole('button', { name: '保存并重新评分' }).click()
  await expect(today(page)).toContainText('今日记录暂无待核对食物')
  expect(errors).toEqual([])
})

test('recommendation selection saves an editable plan and today supports completion', async ({ page }) => {
  const { state, errors } = await setup(page)
  await page.goto('/user/overview')
  await next(page).getByLabel('推荐餐次').selectOption('LUNCH')
  await next(page).getByLabel('用餐场景').selectOption('HOME')
  await next(page).getByLabel('预算上限（元）').fill('25')
  await next(page).getByLabel('可用选餐 / 准备时间（分钟）').fill('20')
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect(next(page).getByRole('radio')).toHaveCount(3)
  expect(state.nextRequests[0]).toEqual({ mealType: 'LUNCH', scene: 'HOME', budget: 25, availableMinutes: 20 })
  await next(page).getByRole('radio').nth(1).check()
  await next(page).getByRole('button', { name: '加入计划', exact: true }).click()
  await expect(next(page).getByLabel('具体行动')).toHaveValue(/玉米、鸡蛋、番茄/)
  await next(page).getByLabel('计划标题').fill('明天午餐准备')
  await next(page).getByRole('button', { name: '保存行动计划', exact: true }).click()
  await expect(next(page).getByRole('button', { name: '已加入计划', exact: true })).toBeDisabled()
  expect(state.planWrites).toHaveLength(1)
  expect(state.planWrites[0].sourceType).toBe('MANUAL')
  expect(state.planWrites[0].plannedDate).toBe(state.date)
  await expect(today(page)).toContainText('明天午餐准备')
  await next(page).getByRole('radio').nth(0).check()
  await next(page).getByRole('radio').nth(1).check()
  await expect(next(page).getByRole('button', { name: '已加入计划', exact: true })).toBeDisabled()
  const saved = today(page).getByRole('listitem').filter({ hasText: '明天午餐准备' })
  await saved.getByRole('button', { name: '完成打卡' }).click()
  await expect(today(page)).not.toContainText('明天午餐准备')
  expect(state.plans.at(-1).status).toBe('DONE')
  expect(errors).toEqual([])
})

test('today failure can recover independently and recommendation failure preserves inputs', async ({ page }) => {
  const { state, errors } = await setup(page); state.failToday = true; state.failNext = true
  await page.goto('/user/overview')
  await expect(today(page)).toContainText('今日数据暂不可用')
  state.failToday = false
  await today(page).getByRole('button', { name: '重试今日总览' }).click()
  await expect(today(page)).toContainText('今天已记录 1 条餐食')
  await next(page).getByLabel('预算上限（元）').fill('17')
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect(next(page).getByRole('alert')).toContainText('搭配暂不可用')
  await expect(next(page).getByLabel('预算上限（元）')).toHaveValue('17')
  state.failNext = false; state.empty = true
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect(next(page)).toContainText('没有同时满足这些条件的方案')
  await expect(next(page).getByRole('radio')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('changing constraints cancels pending results and meal changes invalidate recommendations', async ({ page }) => {
  const { state, errors } = await setup(page)
  let release
  state.delay = new Promise(resolve => { release = resolve })
  await page.goto('/user/overview')
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect.poll(() => state.nextRequests.length).toBe(1)
  await next(page).getByLabel('预算上限（元）').fill('30')
  state.delay = null; release()
  await expect(next(page).getByRole('radio')).toHaveCount(0)
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect(next(page).getByRole('radio')).toHaveCount(3)
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('mealcheck:diet-data-changed', { detail: { kind: 'diet-preferences' } })))
  await expect(next(page).getByRole('radio')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('business midnight refreshes today and clears previous-day recommendations', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-18T15:59:40Z') })
  const { state } = await setup(page)
  await page.goto('/user/overview')
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect(next(page).getByRole('radio')).toHaveCount(3)
  state.date = '2026-10-19'
  await page.clock.runFor(31_000)
  await expect(today(page)).toContainText('2026-10-19')
  await expect(next(page).getByRole('radio')).toHaveCount(0)
})

test('mobile layout supports quick manual entry and readable recommendations without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { errors } = await setup(page)
  await page.goto('/user/overview')
  await today(page).getByRole('button', { name: '手动记录一餐' }).click()
  await expect(page.getByRole('dialog', { name: '手动记录与补记' })).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: '关闭', exact: true }).click()
  await next(page).getByRole('button', { name: '推荐下一餐', exact: true }).click()
  await expect(next(page).getByRole('radio')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/meal-planning-mobile.png', fullPage: true })
  expect(errors).toEqual([])
})
