import { test, expect } from './fixtures/authTest.js'

async function admin(page, handler) {
  await page.addInitScript(() => {

    sessionStorage.setItem('mealcheck_fixture_user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
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

test('RAG chain evaluation submits once and shows linked tool and actual retrieval evidence', async ({ page }) => {
  const errors = [], requests = []
  page.on('pageerror', error => errors.push(error.message))
  const cases = Array.from({ length: 200 }, (_, index) => {
    const noAnswer = index >= 180, missing = index === 0, miss = index === 2, recovered = index === 1
    const called = !noAnswer && !missing, title = miss ? '无关知识条目' : '目标知识'
    return { id: `rag-${String(index + 1).padStart(3, '0')}`, question: `知识问题 ${index + 1}`,
      status: missing || miss ? 'FAILED' : 'PASSED', passed: !missing && !miss,
      toolTracePassed: !missing, expectedTools: noAnswer ? [] : ['search_diet_knowledge'],
      calledTools: called ? ['search_diet_knowledge'] : [],
      missingExpectedTools: missing ? ['search_diet_knowledge'] : [],
      reason: missing ? '缺少预期工具' : miss ? '工具通过，真实检索未命中' : '阶段通过', hardGateFailures: [],
      ragChain: { expectNoAnswer: noAnswer, expectedTitleKeywords: noAnswer ? [] : ['目标'],
        assessmentVersion: 'rag-evidence-v1', annotationStatus: 'PENDING_HUMAN_REVIEW',
        answerability: noAnswer ? 'OUT_OF_SCOPE' : index >= 10 && index < 22 ? 'INSUFFICIENT_EVIDENCE' : 'ANSWERABLE',
        finalEvidenceCovered: !missing && !miss && !noAnswer, finalEvidenceCoverage: missing || miss || noAnswer ? 0 : 1,
        recoveredBySupplement: recovered,
        retrievalStatus: noAnswer ? 'NOT_REQUIRED' : missing ? 'NOT_CALLED' : miss ? 'FAILED' : 'PASSED',
        retrievalPassed: !missing && !miss, firstRelevantRank: missing || miss || recovered || noAnswer ? 0 : 1,
        hitAt3: !missing && !miss && !recovered && !noAnswer, hitAt5: !missing && !miss && !recovered && !noAnswer,
        reciprocalRank: missing || miss || recovered || noAnswer ? 0 : 1, ndcgAt5: missing || miss || recovered || noAnswer ? 0 : 1,
        retrievals: called ? [{ toolCallId: `call-${index}`, query: `实际查询 ${index + 1}`, requestedLimit: 5,
          status: 'SUCCESS', durationMs: 10, hits: [{ id: 7, title: recovered ? '首轮无关知识' : title, score: .9, content: '实际返回的原文。' }] },
          ...(recovered ? [{ toolCallId: `second-${index}`, query: '补充检索查询', requestedLimit: 3, status: 'SUCCESS', durationMs: 8,
            hits: [{ id: 8, title: '目标知识', score: .95, content: '补充检索获得的直接证据。' }] }] : [])] : [] } }
  })
  const result = { runId: 'rag-chain-1', status: 'COMPLETED', mode: 'RAG_CHAIN', verdict: 'CHAIN_EVALUATED',
    caseCount: 200, completedCount: 200, judgedCount: 200, passedCount: 198, failedCount: 2, errorCount: 0,
    passRate: .99, toolTracePassRate: .995, toolTracePassedCount: 199, datasetVersion: 'rag-chain-v2+rag-200-v2+rag-tools-v2', cases }
  await admin(page, async (route, url, send) => {
    if (url.pathname === '/api/admin/assistant/evaluation') {
      if (route.request().method() === 'POST') {
        requests.push(route.request().postDataJSON()); await send(result)
      } else await send(null)
      return true
    }
    if (url.pathname === '/api/admin/assistant/evaluation/history') { await send({ runs: [] }); return true }
    return false
  })
  await page.goto('/admin/rag-evaluation')
  await page.getByRole('button', { name: 'RAG 链路评测（200 题）', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '启动助手评测' })
  await expect(confirmation).toContainText('实际工具调用时执行向量检索')
  await confirmation.getByRole('button', { name: '开始评测' }).click()
  await expect.poll(() => requests).toEqual([{ mode: 'RAG_CHAIN' }])
  await expect(page.locator('.evaluation-summary-grid')).toContainText('链路通过率（工具与检索）')
  await expect(page.locator('.evaluation-summary-grid')).toContainText('99%')
  await expect(page.locator('.rag-chain-metrics')).toContainText('179/180')
  await expect(page.locator('.rag-chain-metrics')).toContainText('20/20')
  await expect(page.locator('.rag-chain-metrics')).toContainText('178/180')
  await expect(page.locator('.rag-chain-metrics')).toContainText('补充检索获得证据')
  await expect(page.locator('.rag-chain-metrics')).toContainText('标注待独立人工复核')
  await page.getByRole('button', { name: '查看完整评测指标' }).click()
  const metrics = page.getByRole('dialog', { name: '完整助手评测指标' })
  await expect(metrics).toContainText('199/200')
  await expect(metrics).toContainText('未使用（规则评分）')
  await expect(metrics).not.toContainText('质量平均分')
  await metrics.getByRole('button', { name: '关闭详情', exact: true }).click()
  await page.getByRole('button', { name: '逐题结果与复核', exact: true }).click()
  await expect(page.getByRole('cell', { name: '未评回答质量', exact: true }).first()).toBeVisible()
  await expect(page.getByRole('cell', { name: '缺少预期工具', exact: true })).toBeVisible()
  const missed = page.getByRole('row').filter({ has: page.getByText('工具通过，真实检索未命中', { exact: true }) })
  await missed.getByText('真实检索：未通过', { exact: true }).click()
  await expect(missed).toContainText('实际查询 3')
  await expect(missed).toContainText('无关知识条目')
  const repaired = page.getByRole('row').filter({ has: page.getByText('rag-002', { exact: true }) })
  await repaired.getByText('真实检索：最终证据已覆盖', { exact: true }).click()
  await expect(repaired).toContainText('首轮相关排名：未命中')
  await expect(repaired).toContainText('补充检索补齐证据')
  await expect(repaired).toContainText('补充检索查询')
  expect(requests).toHaveLength(1)
  expect(errors).toEqual([])
})

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
    if (url.pathname.endsWith('/review')) { reviews++; expect(route.request().postDataJSON()).toMatchObject({ foods: [{ name: '脱敏米饭', category: 'staple', attributes: { fried: false, highOil: null } }], expectedScore: 21, sanitizedConfirmed: true }); await send(null) }
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
  await page.getByLabel('脱敏食物 1 油炸做法', { exact: true }).selectOption('false')
  await expect(page.getByRole('checkbox')).not.toBeChecked()
  await expect(page.getByRole('button', { name: '审核通过并加入回归集' })).toBeDisabled()
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '审核通过并加入回归集' }).click()
  await expect(page.getByRole('button', { name: '运行并保存评测' })).toBeEnabled()
  await page.getByRole('button', { name: '运行并保存评测' }).click()
  await page.getByRole('button', { name: '评测历史与对比', exact: true }).click()
  await page.getByRole('button', { name: '#1 · 2026-10-01 · 失败 1/1' }).click()
  await expect(page.getByText(/历史评测 #1/)).toBeVisible()
  expect(reviews).toBe(1); expect(runs).toBe(1)
})
