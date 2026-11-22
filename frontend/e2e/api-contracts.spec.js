import { test, expect } from './fixtures/authTest.js'

const invalidMessage = '服务返回的数据格式异常，请重新加载后重试。'

test('complete transport with quota degradation cannot be saved as a plan', async ({ page }) => {
  const errors=await setup(page,async ({path,route})=>{
    if(path==='/api/assistant/stream') {
      await route.fulfill({contentType:'text/event-stream',body:'event: complete\ndata: '+JSON.stringify({
        answer:'模型服务额度已用尽，请联系管理员恢复额度后再试。',messageId:1,deliveryStatus:'COMPLETE',
        answerStatus:'DEGRADED',failureCode:'MODEL_QUOTA_EXHAUSTED',upstreamStatus:403})+'\n\n'});return true
    }
    return false
  })
  await page.goto('/user/overview');await page.locator('.assistant-fab').click()
  await page.getByPlaceholder('问问怎么吃更合适').fill('早餐怎么选？')
  await page.getByRole('button',{name:'发送问题'}).click()
  const reply=page.locator('.assistant-message').last()
  await expect(reply).toContainText('模型服务额度已用尽')
  await expect(reply.getByRole('status')).toContainText('未完成全部核验')
  await expect(reply.getByRole('button',{name:'保存为计划'})).toHaveCount(0)
  expect(errors).toEqual([])
})
const record = { id: 1, createdAt: '2026-10-14T04:00:00', revision: 0, metadataVersion: 0, score: 80,
  foods: [{ name: '米饭', category: 'staple', confidence: 0.9, note: null }], summary: '合法餐食', advice: '搭配蔬菜' }
const nutrition = { recordId: 1, recordRevision: 0, version: 0, enabled: false, status: 'DISABLED',
  estimatedFoods: 0, totals: null, items: [{ foodIndex: 0, name: '米饭', grams: null, per100g: null, source: null }] }

async function setup(page, handler = () => false) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'tester', displayName: '测试用户', role: 'USER' }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (await handler({ route, request, path, send })) return
    if (path === '/api/meals/summaries') return send({ content: [{ ...record, foodNames: ['米饭'] }], totalElements: 1, totalPages: 1, page: 0, size: 5 })
    if (path === '/api/meals/1') return send(record)
    if (path === '/api/meals/1/nutrition') return send(nutrition)
    if (path === '/api/profile/goals/current') return send({ goalType: 'balanced', endDate: null, note: null })
    if (path === '/api/profile/ai-budget') return send({ enabled: false })
    if (path === '/api/assistant/conversations') return send([{ id: 7, title: '测试会话' }])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(null)
  })
  return errors
}

test('invalid summary data shows a retryable error and valid retry restores the record list', async ({ page }) => {
  let bad = true
  const errors = await setup(page, async ({ path, send }) => {
    if (path !== '/api/meals/summaries' || !bad) return false
    await send({ content: [{ ...record, score: '80' }], totalElements: 1, totalPages: 1 }); return true
  })
  await page.goto('/user/records')
  await expect(page.getByRole('alert')).toContainText(invalidMessage)
  await expect(page.getByRole('button', { name: '查看餐食详情' })).toHaveCount(0)
  bad = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('button', { name: '查看餐食详情' })).toBeVisible()
  expect(errors).toEqual([])
})

test('invalid detail stays out of the drawer and reload recovers the complete meal', async ({ page }) => {
  let bad = true
  const errors = await setup(page, async ({ path, send }) => {
    if (path !== '/api/meals/1' || !bad) return false
    await send({ ...record, foods: [{ name: { private: 'invalid' }, category: 'staple' }] }); return true
  })
  await page.goto('/user/records')
  await page.getByRole('button', { name: '查看餐食详情' }).click()
  await expect(page.getByRole('alert')).toContainText(invalidMessage)
  await expect(page.getByRole('dialog', { name: '餐食记录详情', exact: true })).toHaveCount(0)
  bad = false
  await page.getByRole('button', { name: '重新加载详情' }).click()
  await expect(page.getByRole('dialog', { name: '餐食记录详情', exact: true })).toContainText('搭配蔬菜')
  expect(errors).toEqual([])
})

test('missing nutrition version blocks edits and a valid reload allows saving with the received versions', async ({ page }) => {
  let bad = true, writes = 0
  const errors = await setup(page, async ({ path, request, send }) => {
    if (path !== '/api/meals/1/nutrition') return false
    if (request.method() === 'PUT') {
      writes++
      expect(request.postDataJSON()).toMatchObject({ expectedRevision: 0, expectedVersion: 0, enabled: true })
      await send({ ...nutrition, enabled: true, status: 'INCOMPLETE', version: 1 }); return true
    }
    if (!bad) return false
    await send({ ...nutrition, version: undefined }); return true
  })
  await page.goto('/user/records')
  await page.getByRole('button', { name: '查看餐食详情' }).click()
  const panel = page.getByRole('region', { name: '可选份量与营养估算' })
  await expect(panel.getByRole('alert')).toContainText(invalidMessage)
  await expect(panel.getByRole('checkbox')).toHaveCount(0)
  await expect(panel.locator('.nutrition-estimate')).toHaveCount(0)
  expect(writes).toBe(0)
  bad = false
  await panel.getByRole('button', { name: '重新加载设置' }).click()
  await panel.getByRole('checkbox').check()
  await panel.getByRole('button', { name: '保存份量与粗略估算' }).click()
  await expect(panel.getByText('已保存可选份量和估算资料。')).toBeVisible()
  expect(writes).toBe(1)
  expect(errors).toEqual([])
})

test('invalid nutrition save response cannot display success or a fabricated estimate', async ({ page }) => {
  const errors = await setup(page, async ({ path, request, send }) => {
    if (path !== '/api/meals/1/nutrition' || request.method() !== 'PUT') return false
    await send({ ...nutrition, version: 1, enabled: true, status: 'ESTIMATED', estimatedFoods: 1,
      totals: { energyKcal: 999, proteinGrams: 1, fatGrams: 1, carbohydrateGrams: 1 } }); return true
  })
  await page.goto('/user/records')
  await page.getByRole('button', { name: '查看餐食详情' }).click()
  const panel = page.getByRole('region', { name: '可选份量与营养估算' })
  await panel.getByRole('checkbox').check()
  await panel.getByRole('button', { name: '保存份量与粗略估算' }).click()
  await expect(panel.getByRole('alert')).toContainText(invalidMessage)
  await expect(panel.locator('.nutrition-estimate')).toHaveCount(0)
  await expect(panel.getByText('已保存可选份量和估算资料。')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('invalid assistant history and stream are recoverable without rendering objects or complete answer actions', async ({ page }) => {
  let badHistory = true, badStream = true
  const errors = await setup(page, async ({ path, route, send }) => {
    if (path === '/api/assistant/conversations/7') {
      await send({ id: 7, messages: [{ role: 'assistant', text: badHistory ? { private: 'bad' } : '合法历史回答' }] }); return true
    }
    if (path === '/api/assistant/stream') {
      await route.fulfill({ contentType: 'text/event-stream', body: badStream
        ? 'event: delta\ndata: {"text":{"private":"bad"}}\n\n'
        : 'event: complete\ndata: {"answer":"合法助手回答"}\n\n' }); return true
    }
    return false
  })
  await page.goto('/user/overview')
  await page.locator('.assistant-fab').click()
  await page.getByRole('button', { name: '测试会话', exact: true }).click()
  await expect(page.getByText(invalidMessage, { exact: true })).toBeVisible()
  badHistory = false
  await page.getByRole('button', { name: '测试会话', exact: true }).click()
  await expect(page.getByText('合法历史回答', { exact: true })).toBeVisible()
  await expect(page.getByText(invalidMessage, { exact: true })).toHaveCount(0)
  await page.getByPlaceholder('问问怎么吃更合适').fill('测试问题')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.assistant-message').last()).toContainText(invalidMessage)
  await expect(page.locator('.assistant-message').last().getByRole('button', { name: '保存为计划' })).toHaveCount(0)
  await expect(page.getByPlaceholder('问问怎么吃更合适')).toBeEnabled()
  badStream = false
  await page.getByPlaceholder('问问怎么吃更合适').fill('再次提问')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('合法助手回答', { exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('historical meal shows typed fallback and visible protein evidence', async ({ page }) => {
  const errors=await setup(page,async ({path,send}) => {
    if(path !== '/api/meals/1') return false
    await send({...record,adviceStatus:'RULE_SERVICE',adviceFailureCode:'MODEL_RATE_LIMITED',foods:[{
      name:'番茄炒蛋',category:'vegetable',confidence:.95,note:'可见炒蛋',
      structureRoles:[{role:'protein',source:'VISIBLE_COMPONENT',evidence:'可见炒蛋'}]
    }]});return true
  })
  await page.goto('/user/records');await page.getByRole('button',{name:'查看餐食详情'}).click()
  const drawer=page.getByRole('dialog',{name:'餐食记录详情',exact:true})
  await expect(drawer.getByRole('status').filter({hasText:'当前展示规则建议'})).toContainText('模型服务限流')
  await expect(drawer.getByTitle('可见炒蛋')).toBeVisible()
  expect(errors).toEqual([])
})
