import { test, expect } from '@playwright/test'

async function openWorkspace(page, role = 'USER') {
  await page.addInitScript(role => {
    localStorage.setItem('mealcheck_token', 'workspace-test-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'demo', displayName: role === 'ADMIN' ? '平台管理员' : '小林', role }))
  }, role)
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const value = path === '/api/profile/goals/current' ? { goalType: 'balanced' }
      : path === '/api/meals/trends' ? { totalMeals: 0, dailyScores: [], commonFoods: [], commonRisks: [] }
      : path === '/api/assistant/conversations' ? [] : null
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) })
  })
  await page.goto('/')
}

async function expectNoPageOverflow(page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

test('desktop user sidebar preserves drafts and remembers its collapsed state', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await openWorkspace(page)
  await expect(page.getByRole('heading', { name: '上传饮食图片' })).toBeVisible()
  await page.screenshot({ path: 'test-results/workspace-user-desktop.png', fullPage: true })
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  await page.getByRole('textbox', { name: '目标说明', exact: true }).fill('每天认真吃早餐')
  await page.getByRole('button', { name: '饮食趋势', exact: true }).click()
  await expect(page.getByRole('heading', { name: '饮食趋势', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '饮食趋势', exact: true })).toHaveAttribute('aria-current', 'page')
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '最近饮食记录' })).toBeVisible()
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '目标说明', exact: true })).toHaveValue('每天认真吃早餐')
  await page.getByRole('button', { name: '折叠侧栏' }).click()
  expect((await page.locator('.workspace-sidebar').boundingBox()).width).toBe(84)
  await page.reload()
  await expect(page.getByRole('button', { name: '展开侧栏' })).toBeVisible()
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '最近饮食记录' })).toBeVisible()
  await expectNoPageOverflow(page)
})

for (const role of ['USER', 'ADMIN']) {
  test(`${role} mobile drawer traps focus, restores focus and closes on navigation`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openWorkspace(page, role)
    const openButton = page.getByRole('button', { name: '打开导航菜单' })
    await openButton.click()
    const drawer = page.getByRole('dialog', { name: role === 'ADMIN' ? '管理员工作栏' : '用户工作栏' })
    await expect(drawer).toBeVisible()
    await expect(page.getByRole('button', { name: '关闭导航菜单' })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(drawer.getByRole('button', { name: role === 'ADMIN' ? '退出登录' : '退出', exact: true })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: '关闭导航菜单' })).toBeFocused()
    await page.screenshot({ path: `test-results/workspace-${role.toLowerCase()}-drawer.png` })
    await page.keyboard.press('Escape')
    await expect(drawer).not.toBeVisible()
    await expect(openButton).toBeFocused()
    await openButton.click()
    await drawer.getByRole('button', { name: role === 'ADMIN' ? '用户管理' : '饮食记录', exact: true }).click()
    await expect(drawer).not.toBeVisible()
    await expect(page.getByRole('heading', { name: role === 'ADMIN' ? '用户管理' : '最近饮食记录', exact: true }).first()).toBeVisible()
    await expectNoPageOverflow(page)
    await page.screenshot({ path: `test-results/workspace-${role.toLowerCase()}-mobile.png`, fullPage: true })
    await openButton.click()
    await page.locator('.sidebar-backdrop').click({ position: { x: 375, y: 300 } })
    await expect(drawer).not.toBeVisible()
    await expect(openButton).toBeFocused()
  })

  test(`${role} workspace fits desktop, tablet and narrow mobile screens`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await openWorkspace(page, role)
    await expect(page.getByRole('heading', { name: role === 'ADMIN' ? '系统概览' : '饮食分析', exact: true })).toBeVisible()
    await page.screenshot({ path: `test-results/workspace-${role.toLowerCase()}-overview.png`, fullPage: true })
    for (const width of [1440, 1024, 768, 390, 360]) {
      await page.setViewportSize({ width, height: 900 })
      await expectNoPageOverflow(page)
    }
    await page.getByRole('button', { name: '打开导航菜单' }).click()
    await page.setViewportSize({ width: 1024, height: 900 })
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.locator('.workspace-frame')).not.toHaveAttribute('inert', '')
    expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden')
  })
}
