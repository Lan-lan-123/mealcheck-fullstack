import { test, expect } from './fixtures/authTest.js'

async function setup(page, { conflict = false, fixedDimensions = false, activeMode = 'SEMANTIC' } = {}) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const active = { model: 'original-model', baseUrl: 'https://old.test/v1', dimension: 512, sendDimensions: true, apiKeyConfigured: true, source: 'ENVIRONMENT' }
  const settings = { version: 0, activeMode, active, draft: null, rebuildRequired: false }
  const state = { activeMode, semanticModel: active.model, semanticAvailable: true, dimension: 512, job: null,
    inventory: { counts: { total: 1, compatible: 1, semantic: 1, local: 0, unknown: 0, outdated: 0 }, chunks: [{ id: 1, title: '蔬菜与蛋白质', model: active.model, mode: 'SEMANTIC', dimension: 512, compatible: true }], truncated: false } }
  const requests = []
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/admin/knowledge/embedding-settings') {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON(); requests.push(body)
        if (conflict) return send({ message: '向量配置已变化，请刷新后重试。' }, 409)
        expect(body.version).toBe(settings.version)
        expect(body.sendDimensions).toBe(!fixedDimensions)
        settings.draft = { ...body, apiKey: undefined, apiKeyConfigured: true, testedAt: null, source: 'ADMIN' }
        settings.version += 1; settings.rebuildRequired = true
      }
      return send(settings)
    }
    if (path === '/api/admin/knowledge/embedding-settings/test') {
      expect(request.postDataJSON()).toEqual({ version: settings.version })
      settings.draft.testedAt = '2026-10-19T10:00:00Z'
      return send({ success: true, model: settings.draft.model, dimension: settings.draft.dimension, elapsedMs: 127, message: '连接成功，返回维度与配置一致。' })
    }
    if (path === '/api/admin/knowledge/embedding-settings/preview') {
      return send({ mode: 'SEMANTIC', previewToken: 'profile-preview', targetModel: settings.draft.model, targetDimension: settings.draft.dimension,
        currentChunks: 1, targetChunks: 1, inserted: 0, updated: 0, deleted: 0, manualChanges: 0, affectedManualChanges: [], sourceEmbeddingRequests: 0, targetEmbeddingRequests: 1 })
    }
    if (path === '/api/admin/knowledge/embedding-settings/apply') {
      expect(request.postDataJSON()).toEqual({ version: settings.version, previewToken: 'profile-preview' })
      state.job = { id: 42, profile_id: 7, status: 'PENDING', embedding_mode: 'SEMANTIC', scope: 'ALL', dimension: settings.draft.dimension, staged: 0, totalChunks: 1 }
      return send({ rebuilding: true, job: state.job, settings })
    }
    if (path === '/api/admin/knowledge/indexing') return send(state)
    if (path === '/api/admin/knowledge/indexing/jobs') return send({ content: state.job ? [state.job] : [], totalElements: state.job ? 1 : 0, totalPages: 1 })
    return send({ content: [], totalElements: 0, totalPages: 0 })
  })
  await page.goto('/admin/knowledge')
  const panel = page.getByRole('region', { name: '向量模型配置' })
  await expect(panel.getByLabel('向量模型名称')).toHaveValue('original-model')
  return { panel, settings, state, requests, errors }
}

test('save and test independent embedding settings before rebuilding and switching the active model', async ({ page }) => {
  const { panel, settings, state, requests, errors } = await setup(page, { fixedDimensions: true, activeMode: 'LOCAL' })
  await expect(panel.getByLabel('向量 API Key')).toHaveValue('')
  await expect(panel.getByLabel('向量 API Key')).toHaveAttribute('type', 'password')
  await panel.getByLabel('向量模型名称').fill('new-embedding-model')
  await panel.getByLabel('向量 Base URL').fill('https://new.test/v1')
  await panel.getByLabel('向量 API Key').fill('new-secret')
  await panel.getByLabel('向量维度', { exact: true }).fill('768')
  await panel.getByLabel('请求指定维度').uncheck()
  await expect(panel.getByRole('button', { name: '测试向量连接' })).toBeDisabled()
  await panel.getByRole('button', { name: '保存待用配置' }).click()
  await expect.poll(() => requests[0]?.apiKey).toBe('new-secret')
  await expect(panel.getByLabel('向量 API Key')).toHaveValue('')
  await expect(panel).toContainText('当前生效：本地哈希向量')
  await expect(panel.getByRole('button', { name: '应用配置并重建全部向量' })).toBeDisabled()
  await panel.getByRole('button', { name: '测试向量连接' }).click()
  await expect(panel.getByRole('status')).toContainText('768 维 · 127 ms')
  await panel.getByRole('button', { name: '应用配置并重建全部向量' }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  await expect(page.getByRole('button', { name: '知识库重建中…' })).toBeDisabled()
  await expect(panel).toContainText('当前生效：本地哈希向量')
  await expect(page.getByLabel('向量生成方式')).toHaveValue('SEMANTIC')
  // Edits to the next draft remain available while a fixed profile is running.
  await panel.getByLabel('向量模型名称').fill('next-draft-model')
  state.job.status = 'SUCCEEDED'
  state.activeMode = 'SEMANTIC'; state.semanticModel = settings.draft.model; state.dimension = 768
  settings.active = settings.draft; settings.activeMode = 'SEMANTIC'; settings.version += 1
  await expect(panel).toContainText('当前生效：new-embedding-model', { timeout: 10000 })
  await expect(panel.getByLabel('向量模型名称')).toHaveValue('next-draft-model')
  expect(errors).toEqual([])
  await page.screenshot({ path: test.info().outputPath('embedding-settings-desktop.png'), fullPage: true })
})

test('configuration conflict preserves edits and refresh reloads the server version', async ({ page }) => {
  const { panel, errors } = await setup(page, { conflict: true })
  await panel.getByLabel('向量模型名称').fill('unsaved-model')
  await panel.getByRole('button', { name: '保存待用配置' }).click()
  await expect(panel.getByRole('alert')).toContainText('请刷新后重试')
  await expect(panel.getByLabel('向量模型名称')).toHaveValue('unsaved-model')
  await panel.getByRole('button', { name: '刷新向量配置' }).click()
  await expect(panel.getByLabel('向量模型名称')).toHaveValue('original-model')
  expect(errors).toEqual([])
})

test('embedding configuration fits a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { panel, errors } = await setup(page)
  await expect(panel.getByRole('button', { name: '保存待用配置' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  expect(errors).toEqual([])
  await page.screenshot({ path: test.info().outputPath('embedding-settings-mobile.png'), fullPage: true })
})
