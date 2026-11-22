import { test, expect } from './fixtures/authTest.js'

async function session(page, role) {
  await page.addInitScript(role => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: role === 'ADMIN' ? 'admin' : 'cost-user', role }))
  }, role)
}

test('administrator saves monetary limits and model prices, refreshes and restores deployment defaults', async ({ page }, testInfo) => {
  await session(page, 'ADMIN')
  let settings = { version: 0, enabled: true, currency: 'CNY', source: 'ENVIRONMENT', globalDailyCost: 0, userDailyCost: 0, systemDailyCost: 0, prices: [] }
  let saved, resetCount = 0
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/admin/ai-cost-settings') {
      if (route.request().method() === 'PUT') {
        saved = route.request().postDataJSON()
        settings = { ...settings, ...saved, source: 'ADMIN', version: settings.version + 1 }
      }
      return send(settings)
    }
    if (path === '/api/admin/ai-cost-settings/reset') {
      resetCount++
      settings = { ...settings, version: settings.version + 1, source: 'ENVIRONMENT', globalDailyCost: 0, userDailyCost: 0, systemDailyCost: 0, prices: [] }
      return send(settings)
    }
    if (path === '/api/admin/ai-budget') return send({ enabled: true, monitorHealthy: true, globalUsage: { attempts: 3, callLimit: 100000, remainingCalls: 99997, spent: .1, reserved: .02, costLimit: settings.globalDailyCost }, pricing: { currency: 'CNY', catalogEntries: settings.prices.length } })
    return send({})
  })
  await page.goto('/admin/monitor')
  await page.getByRole('button', { name: 'AI 调用', exact: true }).click()
  const panel = page.getByRole('region', { name: 'AI 费用设置', exact: true })
  await expect(panel.getByLabel('全站每日金额上限（CNY）')).toHaveValue('0')
  await panel.getByLabel('全站每日金额上限（CNY）').fill('20')
  await panel.getByLabel('每位用户每日金额上限（CNY）').fill('2')
  await panel.getByLabel('系统任务每日金额上限（CNY）').fill('5')
  await panel.getByRole('button', { name: '添加模型价格' }).click()
  await panel.getByLabel('完整接口地址').fill('https://provider.test/v1/chat/completions')
  await panel.getByLabel('模型名称', { exact: true }).fill('paid-model')
  await panel.getByLabel('输入单价', { exact: true }).fill('0.8')
  await panel.getByLabel('输出单价', { exact: true }).fill('2')
  await panel.getByRole('button', { name: '保存费用设置' }).click()
  await expect(panel.getByRole('status')).toContainText('费用配置已保存')
  expect(saved).toMatchObject({ version: 0, globalDailyCost: '20', userDailyCost: '2', systemDailyCost: '5', prices: [{ model: 'paid-model', inputCostPerMillion: '0.8', currency: 'CNY' }] })
  await expect(page.getByLabel('AI 预算监控')).toContainText('独立模型价格配置 1 项')
  await panel.screenshot({ path: testInfo.outputPath('administrator-cost-settings.png') })
  await page.reload()
  await page.getByRole('button', { name: 'AI 调用', exact: true }).click()
  await expect(panel.getByLabel('全站每日金额上限（CNY）')).toHaveValue('20')
  await panel.getByRole('button', { name: '恢复部署费用配置' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(panel.getByLabel('全站每日金额上限（CNY）')).toHaveValue('0')
  expect(resetCount).toBe(1)
  expect(errors).toEqual([])
})

test('ordinary user sets and disables personal budget without changing administrator cap or usage', async ({ page }, testInfo) => {
  await session(page, 'USER')
  await page.setViewportSize({ width: 390, height: 844 })
  let settings = { version: 0, enabled: true, currency: 'CNY', dailyCost: 0, administratorDailyCost: 2, effectiveDailyCost: 2 }
  const saves = []
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/profile/ai-cost-settings') {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON(); saves.push(body)
        settings = { ...settings, version: settings.version + 1, dailyCost: Number(body.dailyCost), effectiveDailyCost: Number(body.dailyCost) > 0 ? Math.min(2, Number(body.dailyCost)) : 2 }
      }
      return send(settings)
    }
    if (path === '/api/profile/ai-budget') return send({ enabled: true, remainingCalls: 97, callLimit: 100, spent: .1, reserved: .02, costLimit: settings.effectiveDailyCost, currency: 'CNY' })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(path.endsWith('/goals/current') ? { goalType: 'balanced' } : {})
  })
  await page.goto('/')
  const notice = page.getByRole('complementary', { name: 'AI 每日额度' }).first()
  await notice.getByRole('button', { name: '设置个人预算' }).click()
  await notice.getByLabel('我的每日金额上限（CNY）').fill('0.5')
  await notice.getByRole('button', { name: '保存个人预算' }).click()
  await expect(notice.getByRole('status')).toContainText('个人预算已保存')
  await expect(notice).toContainText('上限 0.5000 CNY')
  await expect(notice).toContainText('97 / 100')
  await notice.screenshot({ path: testInfo.outputPath('personal-budget-mobile.png') })
  await page.reload()
  await notice.getByRole('button', { name: '设置个人预算' }).click()
  await expect(notice.getByLabel('我的每日金额上限（CNY）')).toHaveValue('0.5')
  await notice.getByLabel('我的每日金额上限（CNY）').fill('0')
  await notice.getByRole('button', { name: '保存个人预算' }).click()
  await expect(notice).toContainText('上限 2.0000 CNY')
  expect(saves).toEqual([{ version: 0, dailyCost: '0.5' }, { version: 1, dailyCost: '0' }])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('conflicting personal settings keep the draft and can be refreshed', async ({ page }) => {
  await session(page, 'USER')
  let version = 0
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/profile/ai-cost-settings') {
      if (route.request().method() === 'PUT') {
        version = 1
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: '预算配置已变化，请刷新配置后重试。' }) })
      }
      return send({ version, enabled: true, currency: 'CNY', dailyCost: version ? 1 : 0, administratorDailyCost: 0, effectiveDailyCost: version ? 1 : 0 })
    }
    if (path === '/api/profile/ai-budget') return send({ enabled: true, remainingCalls: 100, callLimit: 100, costLimit: 0 })
    if (path === '/api/assistant/conversations') return send([])
    if (path === '/api/assistant/proactive') return send({ items: [] })
    return send(path.endsWith('/goals/current') ? { goalType: 'balanced' } : {})
  })
  await page.goto('/')
  const notice = page.getByRole('complementary', { name: 'AI 每日额度' }).first()
  await notice.getByRole('button', { name: '设置个人预算' }).click()
  await notice.getByLabel('我的每日金额上限（CNY）').fill('2')
  await notice.getByRole('button', { name: '保存个人预算' }).click()
  await expect(notice.getByRole('alert')).toContainText('预算配置已变化')
  await expect(notice.getByLabel('我的每日金额上限（CNY）')).toHaveValue('2')
  await notice.getByRole('button', { name: '刷新个人预算配置' }).click()
  await expect(notice.getByLabel('我的每日金额上限（CNY）')).toHaveValue('1')
})
