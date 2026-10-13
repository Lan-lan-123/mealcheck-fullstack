import { test, expect } from '@playwright/test'

async function admin(page, handler) {
  await page.addInitScript(() => {
    localStorage.setItem('mealcheck_token', 'test-admin-token')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const send = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
    if (await handler(route, url, send)) return
    return send(null)
  })
  await page.goto('/')
}

test('admin filters reject late responses from an earlier query', async ({ page }) => {
  let entered, release
  const started = new Promise(resolve => { entered = resolve }), gate = new Promise(resolve => { release = resolve })
  await admin(page, async (route, url, send) => {
    if (url.pathname !== '/api/admin/users') return false
    const name = url.searchParams.get('username')
    if (name === 'old') { entered(); await gate }
    await send({ content: name ? [{ id: 1, username: `${name}-result`, role: 'USER' }] : [], totalElements: name ? 1 : 0, totalPages: 1 }).catch(() => {})
    return true
  })
  await page.getByRole('button', { name: '用户管理', exact: true }).click()
  await page.getByPlaceholder('搜索用户名或昵称').fill('old'); await page.getByRole('button', { name: '筛选', exact: true }).click(); await started
  await page.getByPlaceholder('搜索用户名或昵称').fill('new'); await page.getByRole('button', { name: '筛选', exact: true }).click()
  await expect(page.getByText('new-result', { exact: true })).toBeVisible()
  release()
  await expect(page.getByText('old-result', { exact: true })).not.toBeVisible()
})

test('mobile admin reviews foods with a form and sees immutable run failures', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  let reviews = 0, runs = 0
  const evaluation = { datasetVersion: 'v1', datasetFingerprint: 'abc', scoringVersion: 'rules', caseCount: 1, failedCount: 1, passed: false,
    cases: [{ id: 'correction-1', goal: 'balanced', foods: [{ name: '米饭', category: 'staple' }], expectedScore: 90, actualScore: 21, passed: false }] }
  await admin(page, async (route, url, send) => {
    if (!url.pathname.startsWith('/api/admin/meal-corrections')) return false
    if (url.pathname.endsWith('/review')) { reviews++; expect(route.request().postDataJSON()).toMatchObject({ foods: [{ name: '脱敏米饭', category: 'staple' }], expectedScore: 21, sanitizedConfirmed: true }); await send(null) }
    else if (url.pathname.endsWith('/runs')) {
      if (route.request().method() === 'POST') { runs++; await send({ id: 1 }) }
      else await send(runs ? [{ id: 1, createdAt: '2026-10-01', failedCount: 1, caseCount: 1 }] : [])
    } else if (url.pathname.includes('/evaluation')) await send(evaluation)
    else await send([{ id: 1, revision: 1, beforeFoods: '[]', afterFoods: '[{"name":"米饭","category":"staple"}]', reason: '纠错', status: 'PENDING' }])
    return true
  })
  await page.getByRole('button', { name: '打开导航菜单' }).click()
  await page.getByRole('button', { name: '质量评测', exact: true }).click()
  await page.getByRole('button', { name: '识别纠错评测', exact: true }).click()
  await expect(page.getByRole('cell', { name: '失败', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '纠错审核', exact: true }).click()
  await page.getByRole('button', { name: '审核样本' }).click()
  await page.getByRole('textbox', { name: '脱敏食物名称 1' }).fill('脱敏米饭')
  await page.getByRole('spinbutton', { name: '人工期望评分' }).fill('21')
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '审核通过并加入回归集' }).click()
  await expect(page.getByRole('button', { name: '运行并保存评测' })).toBeEnabled()
  await page.getByRole('button', { name: '运行并保存评测' }).click()
  await page.getByRole('button', { name: '评测历史与对比', exact: true }).click()
  await page.getByRole('button', { name: '#1 · 2026-10-01 · 失败 1/1' }).click()
  await expect(page.getByText(/历史评测 #1/)).toBeVisible()
  expect(reviews).toBe(1); expect(runs).toBe(1)
})
