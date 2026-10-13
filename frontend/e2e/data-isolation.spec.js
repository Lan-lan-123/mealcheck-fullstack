import { test, expect } from '@playwright/test'

async function workspace(page, role, handler) {
  const requests = []
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(role => {
    localStorage.setItem('mealcheck_token', 'data-isolation-test')
    localStorage.setItem('mealcheck_user', JSON.stringify({ username: 'tester', role }))
  }, role)
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    requests.push({ path: url.pathname, method: request.method(), query: Object.fromEntries(url.searchParams) })
    const send = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
    if (await handler?.({ request, url, send })) return
    if (url.pathname === '/api/profile/goals/current') return send({ goalType: 'balanced', note: '已保存的目标', endDate: '' })
    if (url.pathname === '/api/admin/users') return send({ content: [{ id: 2, username: 'page-user', role: 'USER' }], page: Number(url.searchParams.get('page') || 0), totalElements: 40, totalPages: 2 })
    if (url.pathname === '/api/meals') return send({ content: [], page: Number(url.searchParams.get('page') || 0), totalElements: 12, totalPages: 3 })
    if (url.pathname === '/api/meals/trends') return send({ totalMeals: 0, dailyScores: [], commonFoods: [], commonRisks: [] })
    if (url.pathname === '/api/admin/dashboard') return send({ overview: { userCount: 40 } })
    if (url.pathname === '/api/health') return send({ status: 'UP' })
    if (url.pathname === '/api/assistant/conversations') return send([])
    return send(null)
  })
  await page.goto('/')
  return { requests, errors }
}

const reads = state => state.requests.filter(item => item.method === 'GET' && !item.path.startsWith('/api/assistant/'))

test('admin loads only visible data and user pagination fetches only users', async ({ page }) => {
  const state = await workspace(page, 'ADMIN')
  await expect(page.getByText('服务健康', { exact: true })).toBeVisible()
  await expect(page.getByText('UP', { exact: true })).toBeVisible()
  expect(reads(state).map(item => item.path).sort()).toEqual(['/api/admin/dashboard', '/api/health'])
  await page.getByRole('button', { name: '用户管理', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'page-user', exact: true })).toBeVisible()
  const before = reads(state).length
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('.pagination')).toContainText('第 2 / 2 页')
  expect(reads(state).slice(before)).toEqual([{ path: '/api/admin/users', method: 'GET', query: { page: '1', size: '20', role: 'ALL' } }])
  expect(state.errors).toEqual([])
})

test('knowledge failure is confined to knowledge, retry recovers and users stay cached', async ({ page }) => {
  let fail = true
  const state = await workspace(page, 'ADMIN', async ({ url, send }) => {
    if (url.pathname !== '/api/admin/knowledge/chunks') return false
    await send(fail ? { message: '知识库暂时不可用' } : { content: [{ id: 1, title: '恢复后的知识', contentPreview: '内容' }], totalElements: 1, totalPages: 1 }, fail ? 503 : 200)
    return true
  })
  await page.getByRole('button', { name: '用户管理', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'page-user', exact: true })).toBeVisible()
  const userReads = () => reads(state).filter(item => item.path === '/api/admin/users').length
  expect(userReads()).toBe(1)
  await page.getByRole('button', { name: 'RAG 知识库', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('知识库暂时不可用')
  fail = false
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('heading', { name: '恢复后的知识', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '用户管理', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'page-user', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(userReads()).toBe(1)
  expect(state.errors).toEqual([])
})

test('failed meal analytics does not block the meal list', async ({ page }) => {
  const state = await workspace(page, 'ADMIN', async ({ url, send }) => {
    if (url.pathname === '/api/admin/meals/analytics') { await send({ message: '统计暂时不可用' }, 503); return true }
    if (url.pathname === '/api/admin/meals') { await send({ content: [{ id: 1, username: 'independent-meal-user', score: 88, foodNames: ['米饭'] }], totalElements: 1, totalPages: 1 }); return true }
    return false
  })
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'independent-meal-user', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('统计暂时不可用')
  expect(state.errors).toEqual([])
})

test('record filters and pagination preserve an unsaved goal and fetch only records', async ({ page }) => {
  const state = await workspace(page, 'USER')
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  const note = page.getByRole('textbox', { name: '目标说明', exact: true })
  await expect(note).toHaveValue('已保存的目标')
  await note.fill('尚未保存：每天增加蔬菜')
  await page.getByRole('combobox', { name: '当前饮食目标' }).selectOption('light')
  const before = reads(state).length
  await page.getByRole('button', { name: '饮食记录', exact: true }).click()
  await expect(page.locator('.pagination')).toContainText('第 1 / 3 页')
  await page.getByPlaceholder('最低评分').fill('80')
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  await expect.poll(() => reads(state).filter(item => item.query.minScore === '80').length).toBe(1)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('.pagination')).toContainText('第 2 / 3 页')
  expect(reads(state).slice(before).every(item => ['/api/meals', '/api/meals/coverage', '/api/profile/behavior-goals'].includes(item.path))).toBe(true)
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  await expect(note).toHaveValue('尚未保存：每天增加蔬菜')
  await expect(page.getByRole('combobox', { name: '当前饮食目标' })).toHaveValue('light')
  expect(reads(state).filter(item => item.path === '/api/profile/goals/current')).toHaveLength(1)
  expect(state.errors).toEqual([])
})

test('a delayed goal response cannot overwrite a draft edited while loading', async ({ page }) => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const state = await workspace(page, 'USER', async ({ url, send }) => {
    if (url.pathname !== '/api/profile/goals/current') return false
    await gate
    await send({ goalType: 'balanced', note: '服务器上的旧目标' })
    return true
  })
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  const note = page.getByRole('textbox', { name: '目标说明', exact: true })
  await note.fill('加载过程中编辑的新目标')
  release()
  await expect(page.getByText('正在加载当前页面...')).toHaveCount(0)
  await expect(note).toHaveValue('加载过程中编辑的新目标')
  expect(state.errors).toEqual([])
})

test('saving a goal refreshes its profile without requesting records, reports or trends', async ({ page }) => {
  let goal = { goalType: 'balanced', note: '旧目标', endDate: '' }
  const state = await workspace(page, 'USER', async ({ request, url, send }) => {
    if (url.pathname !== '/api/profile/goals/current') return false
    if (request.method() !== 'GET') goal = request.postDataJSON()
    await send(goal)
    return true
  })
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '目标说明', exact: true })).toHaveValue('旧目标')
  await page.getByRole('textbox', { name: '目标说明', exact: true }).fill('新目标')
  await page.getByRole('combobox', { name: '当前饮食目标' }).selectOption('fat_loss')
  const before = state.requests.length
  await page.getByRole('button', { name: '保存目标' }).click()
  await expect(page.getByRole('status')).toHaveText('当前目标已更新')
  expect(state.requests.slice(before)).toHaveLength(1)
  expect(state.requests.at(-1).path).toBe('/api/profile/goals/current')
  await page.getByRole('button', { name: '饮食分析', exact: true }).click()
  await expect(page.locator('.goal-quick-link')).toContainText('减脂目标')
  expect(state.errors).toEqual([])
})

test('pending goal saves do not disable image analysis and preserve the draft', async ({ page }) => {
  let release, entered
  const started = new Promise(resolve => { entered = resolve })
  const gate = new Promise(resolve => { release = resolve })
  const state = await workspace(page, 'USER', async ({ request, url, send }) => {
    if (url.pathname !== '/api/profile/goals/current' || request.method() === 'GET') return false
    entered()
    await gate
    await send(request.postDataJSON())
    return true
  })
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  const note = page.getByRole('textbox', { name: '目标说明', exact: true })
  await expect(note).toHaveValue('已保存的目标')
  await note.fill('正在保存的目标')
  await page.getByRole('button', { name: '保存目标', exact: true }).click()
  await started
  await page.getByRole('button', { name: '饮食分析', exact: true }).click()
  await expect(page.locator('input[type=file]')).toBeEnabled()
  await expect(page.getByRole('button', { name: '开始分析', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '饮食目标', exact: true }).click()
  await expect(note).toHaveValue('正在保存的目标')
  await expect(page.getByRole('button', { name: '保存目标', exact: true })).toBeDisabled()
  // A second edit during the first save must survive its older response.
  await note.fill('保存途中继续编辑的内容')
  release()
  await expect(page.getByRole('status')).toHaveText('当前目标已更新')
  await expect(note).toHaveValue('保存途中继续编辑的内容')
  expect(state.errors).toEqual([])
})
