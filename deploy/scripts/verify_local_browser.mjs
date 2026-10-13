// Exercise the running production frontend without intercepting API requests.
// A short-lived session is supplied through stdin, never command-line arguments.
import { chromium, expect } from '../../frontend/node_modules/@playwright/test/index.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

let input = ''
for await (const chunk of process.stdin) input += chunk
const config = JSON.parse(input)
const checks = []
let passed = false
const browser = await chromium.launch({ headless: true,
  ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {}) })
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  await page.addInitScript(auth => {
    localStorage.setItem('mealcheck_token', auth.token)
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: auth.username, displayName: auth.displayName, role: auth.role || 'USER' }))
  }, config.auth)
  await page.goto(config.baseUrl + '/user/records')
  await page.getByRole('button', { name: '查看餐食详情', exact: true }).click()
  const panel = page.getByRole('region', { name: '可选份量与营养估算' })
  const toggle = panel.getByRole('checkbox', { name: '开启本餐可选份量与估算' })
  await expect(toggle).not.toBeChecked()
  await expect(panel).toContainText('营养估算不准确')
  checks.push('default off and inaccuracy warning visible on running mobile UI')
  if (config.phase === 'empty') {
    await expect(toggle).toBeDisabled()
    await expect(panel).toContainText('当前餐食没有可估算的食物项，请先纠正识别结果。')
    checks.push('empty meal cannot enable an invalid estimate')
  } else {
    await toggle.check()
    await panel.getByRole('spinbutton', { name: config.foodName + ' 份量（克）', exact: true }).fill('150')
    await panel.getByRole('button', { name: '保存份量与粗略估算', exact: true }).click()
    await expect(panel).toContainText('已具备完整估算资料：0 / 1 项食物。')
    await expect(panel.locator('.nutrition-estimate')).toHaveCount(0)
    checks.push('portion alone persists through real API without a fabricated total')
    await panel.getByText('填写每 100 克营养数据（可选）', { exact: true }).click()
    for (const [label, value] of [['能量（kcal / 100g）', '200'], ['蛋白质（g / 100g）', '10'], ['脂肪（g / 100g）', '5'], ['碳水化合物（g / 100g）', '20']]) {
      await panel.getByRole('spinbutton', { name: config.foodName + ' ' + label, exact: true }).fill(value)
    }
    await panel.getByRole('textbox', { name: config.foodName + ' 数据来源', exact: true }).fill('Synthetic deployment fixture, not real nutrition data')
    await panel.getByRole('button', { name: '保存份量与粗略估算', exact: true }).click()
    await expect(panel.locator('.nutrition-estimate')).toContainText('300 kcal')
    await page.getByRole('button', { name: '关闭详情', exact: true }).click()
    await page.getByRole('button', { name: '查看餐食详情', exact: true }).click()
    await expect(panel.locator('.nutrition-estimate')).toContainText('300 kcal')
    checks.push('saved synthetic estimate is restored after reopening')
    await toggle.uncheck()
    await panel.getByRole('button', { name: '保存关闭设置', exact: true }).click()
    await expect(panel).toContainText('已关闭，份量和估算资料已清除。')
    checks.push('disabling clears stored inputs through real API')
  }
  const bounds = await panel.boundingBox()
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390)
  checks.push('nutrition panel fits 390px viewport')
  await panel.scrollIntoViewIfNeeded()
  await expect(panel).toBeInViewport()
  await mkdir(config.output, { recursive: true })
  await page.screenshot({ path: path.join(config.output, config.phase + '-mobile.png'), fullPage: true })
  passed = true
} finally {
  await browser.close()
  await mkdir(config.output, { recursive: true })
  await writeFile(path.join(config.output, config.phase + '-verification.json'), JSON.stringify({ passed, checks }, null, 2))
}
console.log(JSON.stringify({ passed, checks: checks.length }))
