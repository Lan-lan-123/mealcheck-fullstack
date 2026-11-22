import { test, expect } from './fixtures/authTest.js'

async function setup(page) {
  const state = { chunk: { id: 42, title: '知识原题', content: '当前正文', contentPreview: '当前正文', contentRevision: 2,
    source: 'admin', category: 'general', sourceVersion: 'manual', tokenCount: 12, hitCount: 0 }, conflict: false, writes: [], restores: [] }
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const send = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
    if (path === '/api/admin/knowledge/chunks') return send({ content: state.chunk ? [state.chunk] : [], totalElements: state.chunk ? 1 : 0, totalPages: 1, page: 0, size: 8 })
    if (path === '/api/admin/knowledge/chunks/42') {
      if (request.method() === 'PUT') {
        state.writes.push(request.postDataJSON())
        if (state.conflict) return send({ message: '知识已被其他操作修改。草稿已保留，请查看最新正文并比较后再保存。' }, 409)
      } else if (request.method() === 'DELETE') {
        expect(url.searchParams.get('expectedRevision')).toBe('2')
        state.chunk = null
      }
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/admin/knowledge/content-history') return send({ content: [
      { id: 7, chunkId: 42, revision: 1, currentRevision: state.chunk ? 2 : 3, title: '知识原题', operation: 'INSERT', actor: 'editor', reason: '初稿', deleted: !state.chunk, recordedAt: '2026-10-22T12:00:00Z' }
    ], totalElements: 1, totalPages: 1 })
    if (path === '/api/admin/knowledge/content-history/7') return send({ id: 7, chunkId: 42, revision: 1,
      currentRevision: state.chunk ? 2 : 3, snapshot: { title: '知识原题', content: '历史正文，可供恢复' }, current: state.chunk ? [{ title: state.chunk.title, content: state.chunk.content }] : [] })
    if (path === '/api/admin/knowledge/content-history/7/restore') {
      state.restores.push(request.postDataJSON()); state.chunk = { id: 42, title: '知识原题', content: '历史正文，可供恢复', contentPreview: '历史正文，可供恢复', contentRevision: 4, source: 'admin', category: 'general', tokenCount: 10, hitCount: 0 }
      return send({ restored: true })
    }
    if (path === '/api/admin/knowledge/indexing') return send({ activeMode: 'LOCAL', semanticAvailable: false, dimension: 512, activeSplitterStrategy: 'recursive-markdown', job: null,
      inventory: { counts: { total: 1, compatible: 1, local: 1, semantic: 0, unknown: 0, outdated: 0 }, chunks: [{ id: 42, title: '知识原题', mode: 'LOCAL', compatible: true }], truncated: false } })
    return send({ content: [], totalElements: 0, totalPages: 0 })
  })
  await page.goto('/admin/knowledge')
  await expect(page.getByRole('heading', { name: 'RAG 知识库维护' })).toBeVisible()
  return state
}

test('conflicting edit preserves draft and can compare latest content', async ({ page }) => {
  const state = await setup(page)
  state.conflict = true
  const card = page.locator('.knowledge-card')
  await card.getByRole('button', { name: '编辑', exact: true }).click()
  await card.locator('textarea').fill('不能丢失的草稿')
  await card.getByRole('button', { name: '保存', exact: true }).click()
  await expect(card.locator('textarea')).toHaveValue('不能丢失的草稿')
  await expect(page.getByText('知识已被其他操作修改。', { exact: false })).toBeVisible()
  expect(state.writes[0]).toMatchObject({ expectedRevision: 2, content: '不能丢失的草稿' })
  await card.getByRole('button', { name: '对比最新正文' }).click()
  await page.getByRole('button', { name: '查看并比较版本 #7' }).click()
  await expect(page.getByRole('region', { name: '知识版本对比' })).toContainText('当前正文')
  await expect(page.locator('.knowledge-content-comparison')).toContainText('历史正文，可供恢复')
  await expect(card.locator('textarea')).toHaveValue('不能丢失的草稿')
  await page.screenshot({ path: test.info().outputPath('content-conflict-comparison.png'), fullPage: true })
})

test('deleted content can be recovered with an explicit reason on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await setup(page)
  await page.locator('.knowledge-card').getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.locator('.knowledge-card')).toHaveCount(0)
  await page.getByRole('button', { name: '查看全部内容历史与已删除知识' }).click()
  await page.getByRole('checkbox', { name: '仅看已删除知识' }).check()
  await page.getByRole('button', { name: '查看并比较版本 #7' }).click()
  await expect(page.getByRole('button', { name: '恢复此版本正文' })).toBeDisabled()
  await page.getByLabel('恢复原因').fill('恢复误删正文')
  await page.getByRole('button', { name: '恢复此版本正文' }).click()
  await expect(page.locator('.knowledge-card')).toContainText('历史正文，可供恢复')
  expect(state.restores).toEqual([{ expectedRevision: 3, reason: '恢复误删正文' }])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('content-restored-mobile.png'), fullPage: true })
})
