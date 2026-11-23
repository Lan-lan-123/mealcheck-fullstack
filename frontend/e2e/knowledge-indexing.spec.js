import { test, expect } from './fixtures/authTest.js'

async function setup(page, { available = true, active = 'LOCAL', job = null, knowledge = [] } = {}) {
  const chunks = Array.from({ length: 200 }, (_, index) => ({ id: index + 1, title: ['食堂主食的搭配', '牛奶与酸奶选择', '蔬菜摄入与膳食纤维', '运动后如何补充蛋白质'][index % 4] + ` ${index + 1}`, mode: active, model: active === 'SEMANTIC' ? 'qwen3.7-text-embedding-flash' : 'HashEmbeddingService', dimension: 512, compatible: true, generated_at: '2026-10-18T10:00:00' }))
  const state = { activeMode: active, semanticAvailable: available, semanticModel: 'qwen3.7-text-embedding-flash', dimension: 512, job, activeSplitterStrategy: 'recursive-markdown',
    inventory: { counts: { total: 200, compatible: 200, semantic: active === 'SEMANTIC' ? 200 : 0, local: active === 'LOCAL' ? 200 : 0, unknown: 0, outdated: 0 }, chunks, truncated: false } }
  const submissions = []
  const actions = []
  const jobs = new Map(job ? [[job.id, job]] : [])
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let data = { content: [], totalElements: 0, totalPages: 0, page: 0, size: 8 }
    let status = 200
    if (path === '/api/admin/knowledge/indexing/preview') {
      const body = route.request().postDataJSON()
      data = { ...body, previewToken: 'frozen-preview', currentChunks: 200, targetChunks: body.chunkIds?.length ?? 200,
        inserted: 0, updated: 1, deleted: 0, manualChanges: 1, affectedManualChanges: [{ id: 1, title: '人工修改样例', action: '覆盖' }],
        targetModel: body.mode === 'LOCAL' ? '本地哈希向量' : state.semanticModel, targetDimension: 512,
        sourceEmbeddingRequests: 0, targetEmbeddingRequests: body.mode === 'LOCAL' ? 0 : 200, estimatedInputUpperBound: 12000 }
    } else if (path === '/api/admin/knowledge/indexing') {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON()
        expect(body.previewToken).toBe('frozen-preview')
        if (state.previewStale) return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: '知识已变化，请重新预览后提交。' }) })
        const { previewToken, ...submitted } = body
        submissions.push(submitted)
        state.job = { id: 7, status: 'PENDING', source_synced: !!body.chunkIds, embedding_mode: body.mode, splitter_strategy: body.splitterStrategy, scope: body.chunkIds ? 'SELECTED' : 'ALL', staged: 0, totalChunks: body.chunkIds?.length ?? 200 }
        data = state.job
        status = 202
      } else data = state
    } else if (path === '/api/admin/knowledge/chunks') {
      data = { content: knowledge, totalElements: knowledge.length, totalPages: knowledge.length ? 1 : 0, page: 0, size: 8 }
    } else if (path === '/api/admin/knowledge/indexing/jobs') {
      if (state.job) jobs.set(state.job.id, state.job)
      data = { content: [...jobs.values()].reverse(), totalElements: jobs.size, totalPages: 1 }
    } else if (/\/api\/admin\/knowledge\/indexing\/jobs\/\d+/.test(path)) {
      const id = Number(path.split('/')[6])
      const action = path.split('/')[7]
      if (action) {
        const body = route.request().postDataJSON()
        actions.push({ id, action, ...body })
        if (state.job) jobs.set(state.job.id, state.job)
        state.job = action === 'cancel' ? { ...state.job, status: 'CANCELLED', failure_summary: '管理员已终止任务，原向量继续生效。' }
          : action === 'retry' ? { ...state.job, status: 'RETRY', failure_summary: null }
            : { ...state.job, id: 9, status: 'PENDING', embedding_mode: body.mode, staged: 0, failure_summary: null }
        data = state.job
        status = action === 'cancel' ? 200 : 202
      } else data = { ...(jobs.get(id) || state.job), events: [{ created_at: '2026-10-18T10:00:00', code: 'EMBEDDING_TIMEOUT', summary: '向量接口调用超时，将自动重试。' }] }
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
  })
  await page.goto('/admin/knowledge')
  await expect(page.getByRole('heading', { name: 'RAG 知识库维护' })).toBeVisible()
  return { state, submissions, actions }
}

test('preview exposes overwritten edits and a stale plan cannot start a rebuild', async ({ page }) => {
  const { state, submissions } = await setup(page)
  await page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '重建影响预览' })
  await expect(dialog).toContainText('人工修改样例')
  await expect(dialog).toContainText('并非计费 token')
  expect(submissions).toEqual([])
  state.previewStale = true
  await dialog.getByRole('button', { name: '确认影响并提交' }).click()
  await expect(dialog).toContainText('请重新预览')
  await expect(dialog.getByRole('button', { name: '确认影响并提交' })).toBeDisabled()
  expect(submissions).toEqual([])
  state.previewStale = false
  await dialog.getByRole('button', { name: '重新预览' }).click()
  await dialog.getByRole('button', { name: '确认影响并提交' }).click()
  await expect(dialog).toHaveCount(0)
  expect(submissions).toHaveLength(1)
})

test('administrator cancels a leased job and resubmits with current selected mode', async ({ page }) => {
  const { actions, submissions } = await setup(page, { job: { id: 7, status: 'RUNNING', scope: 'ALL', embedding_mode: 'SEMANTIC', source_synced: true, staged: 20, totalChunks: 200 } })
  await page.getByRole('button', { name: '终止任务 #7', exact: true }).click()
  await expect(page.locator('.knowledge-indexing').getByRole('status')).toContainText('任务已终止')
  await expect(page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true })).toBeEnabled()
  await page.getByLabel('向量生成方式').selectOption('LOCAL')
  await page.getByRole('button', { name: '重新提交 #7', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  await expect(page.getByRole('button', { name: '知识库重建中…' })).toBeDisabled()
  expect(actions).toEqual([{ id: 7, action: 'cancel' }])
  expect(submissions).toEqual([{ mode: 'LOCAL', splitterStrategy: 'recursive-markdown' }])
})

test('failure history exposes safe cause and retry and configuration invalidation permits resubmit', async ({ page }) => {
  const { state, actions } = await setup(page, { job: { id: 6, status: 'FAILED', scope: 'ALL', embedding_mode: 'SEMANTIC', dimension: 512, signature: 'semantic-v1:fixture:512', totalChunks: 200, staged: 50, failure_summary: '向量接口鉴权失败，请检查 API Key、模型权限或账户额度后重试。' } })
  await expect(page.getByText('向量接口鉴权失败', { exact: false })).toBeVisible()
  await page.getByText('任务历史与恢复', { exact: true }).click()
  await page.getByRole('button', { name: '查看任务 #6 记录', exact: true }).click()
  await expect(page.getByText('向量接口调用超时，将自动重试。', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '重试任务 #6', exact: true }).click()
  await expect(page.getByRole('button', { name: '终止任务 #6', exact: true })).toBeVisible()
  state.job = { ...state.job, status: 'INVALIDATED', failure_summary: '模型、接口或维度已变化，旧任务已停止。' }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.locator('.knowledge-indexing').getByRole('status')).toContainText('任务配置已失效')
  await expect(page.getByRole('button', { name: '重试任务 #6', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '重新提交 #6', exact: true })).toBeEnabled()
  expect(actions).toEqual([{ id: 6, action: 'retry' }])
  await page.screenshot({ path: test.info().outputPath('knowledge-job-recovery.png'), fullPage: true })
})

test('administrator selects semantic vectors and sees atomic completion', async ({ page }) => {
  const { state, submissions } = await setup(page)
  await page.getByLabel('向量生成方式').selectOption('SEMANTIC')
  await page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  await expect(page.getByRole('button', { name: '知识库重建中…' })).toBeDisabled()
  await expect(page.getByLabel('向量生成方式')).toBeDisabled()
  expect(submissions).toEqual([{ mode: 'SEMANTIC', splitterStrategy: 'recursive-markdown' }])
  await expect(page.getByText('当前生效：本地哈希向量 · 512 维', { exact: true })).toBeVisible()
  state.job = { ...state.job, status: 'RUNNING', source_synced: true, staged: 75 }
  await expect(page.locator('.knowledge-indexing').getByRole('status')).toContainText('75 / 200')
  state.activeMode = 'SEMANTIC'
  state.inventory.counts = { total: 200, compatible: 200, semantic: 200, local: 0, unknown: 0, outdated: 0 }
  state.inventory.chunks = state.inventory.chunks.map(row => ({ ...row, mode: 'SEMANTIC', model: state.semanticModel }))
  state.job = { ...state.job, status: 'SUCCEEDED', staged: 0 }
  await expect(page.getByText('当前生效：语义向量模型（qwen3.7-text-embedding-flash） · 512 维')).toBeVisible()
  await expect(page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true })).toBeEnabled()
  await page.screenshot({ path: test.info().outputPath('knowledge-indexing-desktop.png'), fullPage: true })
  await page.getByLabel('向量生成方式').selectOption('LOCAL')
  await page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  expect(submissions.at(-1)).toEqual({ mode: 'LOCAL', splitterStrategy: 'recursive-markdown' })
})

for (const [strategy, label] of [['langchain4j-recursive', 'B · LangChain4j 递归切分'], ['structural-markdown', 'C · 结构感知切分'], ['langchain4j-enhanced', 'D · LangChain4j 增强切分']]) {
  test(`administrator selects ${strategy} and sees the persisted source selection`, async ({ page }) => {
    const { state, submissions } = await setup(page)
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect(page.getByRole('button', { name: label, exact: true })).toHaveAttribute('aria-pressed', 'true')
    await page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true }).click()
    await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
    expect(submissions).toEqual([{ mode: 'LOCAL', splitterStrategy: strategy }])
    await expect(page.getByRole('button', { name: label, exact: true })).toBeDisabled()
    await expect(page.getByText(`本次任务切分：${label}`, { exact: true })).toBeVisible()
    state.activeSplitterStrategy = strategy
    state.job = { ...state.job, status: 'SUCCEEDED', source_synced: true }
    await expect(page.getByText(`当前知识源：${label}`, { exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: label, exact: true })).toHaveAttribute('aria-pressed', 'true')
    await page.screenshot({ path: test.info().outputPath(`knowledge-${strategy}.png`), fullPage: true })
  })
}

test('selected knowledge updates never submit a splitter even if another splitter is selected', async ({ page }) => {
  const { submissions } = await setup(page)
  await page.getByRole('button', { name: 'C · 结构感知切分', exact: true }).click()
  await page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true }).click()
  await page.getByRole('button', { name: '仅生成选中知识', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  expect(submissions).toEqual([{ mode: 'LOCAL', chunkIds: [1] }])
})

test('missing semantic configuration leaves local mode usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { submissions } = await setup(page, { available: false })
  await expect(page.getByLabel('向量生成方式').locator('option[value="SEMANTIC"]')).toBeDisabled()
  await expect(page.getByText('语义向量模型尚未配置。', { exact: false })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('knowledge-indexing-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  expect(submissions).toEqual([{ mode: 'LOCAL', splitterStrategy: 'recursive-markdown' }])
})

test('failed persisted job keeps the previous mode and permits retry after reload', async ({ page }) => {
  const { submissions } = await setup(page, { job: { id: 6, status: 'FAILED', embedding_mode: 'SEMANTIC', totalChunks: 150, staged: 50 } })
  await expect(page.locator('.knowledge-indexing').getByRole('status')).toContainText('原向量方式保持生效')
  await expect(page.getByText('当前生效：本地哈希向量 · 512 维', { exact: true })).toBeVisible()
  await page.getByLabel('向量生成方式').selectOption('SEMANTIC')
  await page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  expect(submissions).toEqual([{ mode: 'SEMANTIC', splitterStrategy: 'recursive-markdown' }])
  await page.reload()
  await expect(page.getByRole('button', { name: '知识库重建中…' })).toBeDisabled()
})

test('vector map selection survives filters and submits only selected knowledge', async ({ page }) => {
  const { state, submissions } = await setup(page, { active: 'SEMANTIC' })
  await page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true }).click()
  await page.getByLabel('搜索向量明细').fill('牛奶')
  await expect(page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '选择知识 2：牛奶与酸奶选择 2', exact: true }).click()
  await expect(page.getByText('已选 2 条', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '仅生成选中知识', exact: true }).click()
  await page.getByRole('button', { name: '确认影响并提交', exact: true }).click()
  expect(submissions).toEqual([{ mode: 'SEMANTIC', chunkIds: [1, 2] }])
  await expect(page.locator('.knowledge-indexing').getByRole('status')).toContainText('选中知识')
  await expect(page.locator('.knowledge-indexing').getByRole('status')).toContainText('0 / 2')
  state.job = { ...state.job, status: 'RUNNING', staged: 1 }
  await expect(page.getByRole('progressbar', { name: '向量生成进度' })).toHaveAttribute('aria-valuenow', '50')
  state.job = { ...state.job, status: 'SUCCEEDED' }
  await expect(page.getByText('已选 0 条', { exact: true })).toBeVisible()
  await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100')
})

test('partial conversion to a different provider is disabled and explains full rebuild', async ({ page }) => {
  const { submissions } = await setup(page)
  await page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true }).click()
  await page.getByLabel('向量生成方式').selectOption('SEMANTIC')
  await expect(page.getByRole('button', { name: '仅生成选中知识', exact: true })).toBeDisabled()
  await expect(page.getByText('当前选择了另一种方式，请使用整库重建完成切换。', { exact: false })).toBeVisible()
  expect(submissions).toEqual([])
  await expect(page.getByRole('button', { name: '重建系统 RAG 知识库', exact: true })).toBeEnabled()
})

test('details show actual provider and unknown historical generation time on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { state } = await setup(page, { active: 'SEMANTIC' })
  state.inventory.chunks[0] = { ...state.inventory.chunks[0], mode: 'LOCAL', model: 'HashEmbeddingService', compatible: false, generated_at: null }
  state.inventory.counts = { total: 200, compatible: 199, semantic: 199, local: 1, unknown: 0, outdated: 1 }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await page.getByLabel('筛选向量状态').selectOption('outdated')
  await expect(page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看明细列表', exact: true }).click()
  await expect(page.getByRole('table').getByText('HashEmbeddingService · 512 维')).toBeVisible()
  await expect(page.getByText('历史生成时间未记录')).toBeVisible()
  await page.getByRole('checkbox', { name: '选择知识 1：食堂主食的搭配 1', exact: true }).check()
  await expect(page.getByText('已选 1 条', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('knowledge-vector-details-mobile.png'), fullPage: true })
})

test('knowledge cards, details and vector inventory separate estimated text from reported input usage', async ({ page }) => {
  const knowledge = [{ id: 1, title: '用量展示样例', source: 'admin', content: '正文样例', contentPreview: '正文样例',
    category: 'general', sourceVersion: 'manual', tokenCount: 400, contentLength: 400, vectorDimension: 512,
    embeddingInputTokens: 322, embeddingTotalTokens: 322, hitCount: 0 }]
  const { state } = await setup(page, { active: 'SEMANTIC', knowledge })
  const card = page.locator('.knowledge-card').filter({ hasText: '用量展示样例' })
  await expect(card.getByText('正文估算 400 tokens · 400 字', { exact: true })).toBeVisible()
  await expect(card.getByText('实际输入 322 tokens', { exact: true })).toBeVisible()
  await card.getByRole('button', { name: '查看详情', exact: true }).click()
  await expect(page.locator('.knowledge-modal')).toContainText('实际输入 322 tokens · 接口总用量 322 tokens')
  await expect(page.locator('.knowledge-modal')).toContainText('接口实际输入包含标题和正文')
  await page.locator('.knowledge-modal').getByRole('button', { name: '关闭', exact: true }).click()
  state.inventory.chunks[0] = { ...state.inventory.chunks[0], estimated_tokens: 400, embedding_input_tokens: 322, embedding_total_tokens: 322 }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true })).toContainText('实际输入 322 tokens')
  await page.getByRole('button', { name: '查看明细列表', exact: true }).click()
  const row = page.getByRole('row').filter({ has: page.getByRole('checkbox', { name: '选择知识 1：食堂主食的搭配 1', exact: true }) })
  await expect(row).toContainText('正文估算 400 tokens')
  await expect(row).toContainText('实际输入 322 tokens')
  await page.screenshot({ path: test.info().outputPath('knowledge-token-usage-desktop.png'), fullPage: true })
})

test('mobile usage distinguishes total-only, zero and unknown history without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { state } = await setup(page, { active: 'SEMANTIC' })
  state.inventory.chunks[0] = { ...state.inventory.chunks[0], embedding_total_tokens: 44 }
  state.inventory.chunks[1] = { ...state.inventory.chunks[1], embedding_input_tokens: 0, embedding_total_tokens: 0 }
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.getByRole('button', { name: '选择知识 1：食堂主食的搭配 1', exact: true })).toContainText('接口总用量 44 tokens')
  await expect(page.getByRole('button', { name: '选择知识 2：牛奶与酸奶选择 2', exact: true })).toContainText('实际输入 0 tokens')
  await expect(page.getByRole('button', { name: '选择知识 3：蔬菜摄入与膳食纤维 3', exact: true })).toContainText('接口用量未记录')
  await page.getByRole('button', { name: '查看明细列表', exact: true }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('knowledge-token-usage-mobile.png'), fullPage: true })
})
