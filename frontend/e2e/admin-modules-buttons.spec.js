import { test, expect } from './fixtures/authTest.js'

test('shared buttons preserve form semantics, refs and prevent repeat busy actions', async ({ page }) => {
  await page.goto('/e2e/fixtures/buttonHarness.html')
  await page.getByRole('button', { name: '普通操作', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('点击 1 次，提交 0 次')
  await page.getByRole('button', { name: '提交表单' }).click()
  await expect(page.getByRole('status')).toHaveText('点击 1 次，提交 1 次')
  await page.getByRole('button', { name: '保存一次' }).click()
  const busy = page.getByRole('button', { name: '保存中' })
  await expect(busy).toBeDisabled()
  await expect(busy).toHaveAttribute('aria-busy', 'true')
  await busy.evaluate(button => button.click())
  await expect(page.getByRole('button', { name: '不可操作' })).toBeDisabled()
  await expect(page.getByRole('status')).toHaveText('点击 2 次，提交 1 次')
  await page.getByRole('button', { name: '聚焦普通操作' }).click()
  await expect(page.getByRole('button', { name: '普通操作', exact: true })).toBeFocused()
})

test('administrator initially loads only the active page and loads evaluation on demand', async ({ page }) => {
  const modules = []
  page.on('request', request => { if (request.url().includes('/src/pages/admin/')) modules.push(request.url()) })
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const data = path === '/api/admin/knowledge/indexing' ? { activeMode: 'LOCAL', semanticAvailable: false, semanticModel: 'qwen3.7-text-embedding-flash', dimension: 512, job: null }
      : path === '/api/admin/dashboard' ? { overview: {} } : path === '/api/health' ? { status: 'UP' }
      : path === '/api/admin/assistant/evaluation' ? { status: 'IDLE', cases: [] }
      : path.includes('/history') ? { runs: [] } : path.endsWith('/conversations') ? [] : { content: [], totalElements: 0, totalPages: 0, page: 0, size: 20 }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) })
  })
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '用户管理', exact: true, level: 2 })).toBeVisible()
  expect(modules.some(url => url.includes('AssistantEvaluationPanel.jsx'))).toBe(false)
  expect(modules.some(url => url.includes('KnowledgeManagementPanel.jsx'))).toBe(false)
  await page.getByRole('button', { name: '质量评测', exact: true }).click()
  await page.getByRole('button', { name: '助手评测', exact: true }).click()
  await expect.poll(() => modules.some(url => url.includes('AssistantEvaluationPanel.jsx'))).toBe(true)
  await page.getByRole('button', { name: 'RAG 知识库', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'RAG 知识库维护' })).toBeVisible()
  expect(modules.some(url => url.includes('KnowledgeManagementPanel.jsx'))).toBe(true)
  const systems = page.getByRole('button', { name: '系统管理', exact: true })
  if (await systems.getAttribute('aria-expanded') === 'false') await systems.click()
  await page.getByRole('button', { name: '系统监控', exact: true }).click()
  await page.getByRole('button', { name: 'AI 调用', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'AI 调用与耗时' })).toBeVisible()
  await page.getByRole('button', { name: '用户管理', exact: true }).click()
  await page.getByRole('button', { name: '系统监控', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'AI 调用与耗时' })).toBeVisible()
})
