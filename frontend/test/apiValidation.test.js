import test from 'node:test'
import assert from 'node:assert/strict'
import { ResponseValidationError, validateApiResponse, assistantEvent } from '../src/api/validation.js'
import { clearSession, mealDetail, listMealSummaries, currentGoal, updateCurrentGoal, streamDietAssistant, analyzeMeal } from '../src/api/client.js'
import { resolveMealAnalysis } from '../src/utils/mealAnalysisJob.js'

const record = { id: 1, createdAt: '2026-10-14T04:00:00', score: 0, revision: 0, metadataVersion: 0,
  foods: [{ name: '米饭', category: 'staple', confidence: 0.9, note: null }] }
const analysis = { recordId: 1, revision: 0, recognition: { foods: record.foods }, evaluation: { score: 0 } }
const report = { periodStart: '2026-10-08', periodEnd: '2026-10-14', averageScore: 0, totalMeals: 0 }
const zero = { energyKcal: 0, proteinGrams: 0, fatGrams: 0, carbohydrateGrams: 0 }
const nutrition = { recordId: 1, recordRevision: 0, version: 0, enabled: true, status: 'INCOMPLETE', estimatedFoods: 0, totals: null,
  items: [{ foodIndex: 0, name: '米饭', grams: null, per100g: null, source: null, estimate: null }] }
const parse = (path, value, method) => validateApiResponse(path, value, method)
const invalid = action => assert.throws(action, error => error instanceof ResponseValidationError && error.code === 'INVALID_API_RESPONSE')

test('partial outcome and reasons survive SSE and persisted history validation', () => {
  const partial = { summary: '有依据的部分', answerStatus: 'PARTIAL', answerReasonCodes: ['UNSUPPORTED_STRENGTH'], deliveryStatus: 'COMPLETE' }
  const value = assistantEvent('complete', partial)
  assert.equal(value.answerStatus, 'PARTIAL'); assert.deepEqual(value.answerReasonCodes, ['UNSUPPORTED_STRENGTH'])
  const history = parse('/api/assistant/conversations/1', { id: 1, messages: [{ ...partial, role: 'assistant', text: partial.summary }] })
  assert.equal(history.messages[0].answerStatus, 'PARTIAL'); assert.deepEqual(history.messages[0].answerReasonCodes, value.answerReasonCodes)
})

test('a complete SSE preserves degraded model status and quota reason', () => {
  const value=assistantEvent('complete',{summary:'模型额度已用尽',deliveryStatus:'COMPLETE',answerStatus:'DEGRADED',failureCode:'MODEL_QUOTA_EXHAUSTED',upstreamStatus:403})
  assert.equal(value.answerStatus,'DEGRADED');assert.equal(value.failureCode,'MODEL_QUOTA_EXHAUSTED');assert.equal(value.upstreamStatus,403)
  invalid(()=>assistantEvent('complete',{summary:'x',answerStatus:'UNKNOWN'}))
  invalid(()=>assistantEvent('complete',{summary:'x',upstreamStatus:'403'}))
})

test('meal queue stage and recovery message survive response validation', () => {
  const state = parse('/api/meals/analysis-requests', { key: 'key', status: 'PENDING', stage: 'QUEUED',
    message: '处理进程中断，正在等待自动恢复', retryAfterSeconds: 3 })
  assert.equal(state.stage, 'QUEUED')
  assert.match(state.message, /自动恢复/)
  invalid(() => parse('/api/meals/analysis-requests', { key: 'key', status: 'PENDING', stage: 'GUESSING' }))
})

function mockClient(t, response) {
  clearSession()
  const storage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => null }
  t.after(() => { globalThis.localStorage = storage })
  t.mock.method(globalThis, 'fetch', async url => url.endsWith('/api/auth/csrf')
    ? new Response(JSON.stringify({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' })) : response())
}

test('a new account can load the workspace when the current-goal endpoint has no saved goal', async t => {
  mockClient(t, () => new Response(null, { status: 200 }))
  assert.equal(await currentGoal(), null)
  assert.deepEqual(parse('/api/profile/goals/current', { goalType: 'balanced' }),
    { goalType: 'balanced', endDate: null, note: null })
  invalid(() => parse('/api/profile/goals/current', {}))
  invalid(() => parse('/api/profile/goals/current', null, 'PUT'))
})

test('valid backend meals preserve zero scores, nullable food notes and normalized display defaults', () => {
  const parsed = parse('/api/meals/1', record)
  assert.equal(parsed.score, 0)
  assert.equal(parsed.foods[0].note, null)
  assert.deepEqual(parsed.riskTags, [])
  assert.equal(parsed.imageUrl, null)
  const page = parse('/api/meals/summaries?size=5', { content: [{ ...record, foodNames: ['米饭'] }], totalElements: 1, totalPages: 1, size: 5 })
  assert.deepEqual(page.content[0].foodNames, ['米饭'])
  assert.equal(parse('/api/meals', [record])[0].id, 1)
  invalid(() => parse('/api/meals?page=0', [record]))
})

test('food risk attributes preserve unknown values and reject coercion while accepting old meals', () => {
  assert.equal(parse('/api/meals/1', record).foods[0].attributes, null)
  const attributes = { fried: true, highOil: null }
  assert.deepEqual(parse('/api/meals/1', { ...record, foods: [{ ...record.foods[0], attributes }] }).foods[0].attributes, attributes)
  for (const value of [{ fried: 'true', highOil: null }, { fried: null, highOil: 1 }, []]) {
    invalid(() => parse('/api/meals/1', { ...record, foods: [{ ...record.foods[0], attributes: value }] }))
  }
})

test('bad roots, nested fields and scalar types fail before reaching the workspace', () => {
  for (const value of [null, [], 'private response', { ...record, score: '80' }, { ...record, score: NaN },
    { ...record, score: 101 }, { ...record, foods: {} }, { ...record, foods: [{ name: {}, category: 'staple' }] },
    { ...record, foods: [{ name: '米饭', category: 'staple', confidence: 2 }] }, { ...record, riskTags: [false] }]) {
    invalid(() => parse('/api/meals/1', value))
  }
  invalid(() => parse('/api/meals/summaries', { content: [], totalElements: '0', totalPages: 0 }))
  invalid(() => parse('/api/meals/summaries', { content: [], totalElements: 0, totalPages: -1 }))
})

test('critical response IDs must match the detail or mutation endpoint', () => {
  invalid(() => parse('/api/meals/2', record))
  invalid(() => parse('/api/meals/2/metadata', record, 'PUT'))
  invalid(() => parse('/api/meals/2/corrections', analysis, 'POST'))
  invalid(() => parse('/api/meals/2/nutrition', nutrition))
  invalid(() => parse('/api/assistant/conversations/2', { id: 1, messages: [] }))
  invalid(() => parse('/api/reports/weekly/jobs/2', { id: 1, status: 'PENDING' }))
  invalid(() => parse('/api/meals/analysis-requests/key', { key: 'different-key', status: 'PENDING' }))
})

test('task enums and successful task results are checked recursively', () => {
  assert.equal(parse('/api/meals/analysis-requests', { key: 'key', status: 'PENDING' }).result, null)
  assert.equal(parse('/api/meals/analysis-requests', { key: 'key', status: 'SUCCEEDED', result: analysis }).result.evaluation.score, 0)
  for (const value of [{ status: 'PENDING' }, { key: 'key', status: 'FINISHED' }, { key: 'key', status: 'SUCCEEDED' },
    { key: 'key', status: 'SUCCEEDED', result: { ...analysis, evaluation: { score: '0' } } }]) {
    invalid(() => parse('/api/meals/analysis-requests', value))
  }
  invalid(() => parse('/api/reports/weekly/jobs', { id: 1, status: 'SUCCEEDED' }))
  assert.equal(parse('/api/reports/weekly/jobs', { id: 1, status: 'SUCCEEDED', report }).report.averageScore, 0)
})

test('null latest report is valid while invalid reports, goals and trends are rejected', () => {
  assert.equal(parse('/api/reports/weekly/latest', null), null)
  invalid(() => parse('/api/reports/weekly/latest', { ...report, nextWeekSuggestions: [5] }))
  invalid(() => parse('/api/reports/weekly/latest', { ...report, totalMeals: undefined }))
  invalid(() => parse('/api/meals/trends', { averageScore: 80, dailyAverageScores: [{ label: '今天', value: '80' }] }))
  invalid(() => parse('/api/profile/goals/current', { goalType: 'unknown' }))
  assert.deepEqual(parse('/api/profile/goals/current', { goalType: 'balanced' }), { goalType: 'balanced', endDate: null, note: null })
  const tracked = parse('/api/reports/weekly/latest', { ...report, tracking: { goals: [{
    goal: { id: 1, kind: 'MEALS_DAILY', target: 2, startDate: '2026-10-08', endDate: null, active: true, version: 0 },
    periodStart: '2026-10-08', periodEnd: '2026-10-14', completed: 0, remaining: 2, achieved: false
  }] } })
  assert.equal(tracked.tracking.goals[0].goal.endDate, null)
})

test('nutrition preserves unknowns and explicit zeros and accepts fully estimated zero totals', () => {
  const partial = parse('/api/meals/1/nutrition', { ...nutrition, items: [{ ...nutrition.items[0], per100g: { energyKcal: 0 } }] })
  assert.equal(partial.items[0].per100g.energyKcal, 0)
  assert.equal(partial.items[0].per100g.proteinGrams, null)
  assert.equal(partial.totals, null)
  const estimated = parse('/api/meals/1/nutrition', { ...nutrition, status: 'ESTIMATED', estimatedFoods: 1, totals: zero,
    items: [{ ...nutrition.items[0], grams: 100, per100g: zero, estimate: zero }] })
  assert.deepEqual(estimated.totals, zero)
})

test('nutrition rejects missing versions, duplicate items, incompatible status and fabricated totals', () => {
  for (const value of [{ ...nutrition, version: undefined }, { ...nutrition, recordRevision: undefined }, { ...nutrition, version: '0' },
    { ...nutrition, enabled: 'true' }, { ...nutrition, status: 'DISABLED' }, { ...nutrition, status: 'CALCULATED' },
    { ...nutrition, items: [nutrition.items[0], nutrition.items[0]] }, { ...nutrition, estimatedFoods: 2 },
    { ...nutrition, totals: zero }, { ...nutrition, status: 'ESTIMATED', totals: zero, estimatedFoods: 1 },
    { ...nutrition, items: [{ ...nutrition.items[0], grams: Infinity }] },
    { ...nutrition, status: 'STALE', items: [{ ...nutrition.items[0], grams: 100 }] },
    { ...nutrition, enabled: false, status: 'DISABLED', items: [{ ...nutrition.items[0], per100g: zero }] },
    { ...nutrition, items: [{ ...nutrition.items[0], per100g: { ...zero, proteinGrams: 101 } }] }]) {
    invalid(() => parse('/api/meals/1/nutrition', value))
  }
})

test('assistant histories, references and pagination cursors are validated before rendering', () => {
  invalid(() => parse('/api/assistant/conversations', [{ id: '1', title: 'history' }]))
  invalid(() => parse('/api/assistant/conversations/1', { id: 1, messages: [{ role: 'assistant', text: {} }] }))
  invalid(() => parse('/api/assistant/conversations/1', { id: 1, messages: [], hasMore: true }))
  invalid(() => parse('/api/assistant/ask', { answer: 'reply', references: [{ title: 'source', content: {} }] }))
  invalid(() => parse('/api/assistant/ask', { answer: 'reply', riskLevel: 'URGENT' }))
  invalid(() => parse('/api/assistant/proactive', { items: 'advice' }))
  const parsed = parse('/api/assistant/conversations/1', { id: 1, messages: [{ role: 'assistant', text: 'partial', deliveryStatus: 'CANCELLED', messageId: 1 }] })
  assert.equal(parsed.messages[0].deliveryStatus, 'CANCELLED')
  assert.equal(parsed.hasMore, false)
})

test('recognized assistant events reject malformed payloads; future events remain ignorable', () => {
  invalid(() => assistantEvent('delta', { text: {} }))
  invalid(() => assistantEvent('complete', { answer: null }))
  invalid(() => assistantEvent('meta', { conversationId: '1' }))
  assert.equal(assistantEvent('future-feature', {}), null)
})

test('validation errors identify a field without exposing response content', () => {
  try { parse('/api/meals/1', { ...record, score: 'secret raw content' }) } catch (error) {
    assert.equal(error.path, '/api/meals/1.score')
    assert.equal(error.message, '服务返回的数据格式异常，请重新加载后重试。')
    assert.equal(JSON.stringify(error).includes('secret raw content'), false)
    return
  }
  assert.fail('validation must fail')
})

test('API wrappers enforce read and write contracts and never publish invalid mutation results', async t => {
  const originalWindow = globalThis.window
  let events = 0, value = { ...record, foods: 'bad' }
  globalThis.window = { dispatchEvent: () => { events++ } }
  t.after(() => { globalThis.window = originalWindow })
  mockClient(t, () => new Response(JSON.stringify(value)))
  await assert.rejects(mealDetail(1), { code: 'INVALID_API_RESPONSE' })
  await assert.rejects(listMealSummaries(), { code: 'INVALID_API_RESPONSE' })
  value = { goalType: 'balanced', note: {} }
  await assert.rejects(updateCurrentGoal({ goalType: 'balanced', endDate: null, note: null }), { code: 'INVALID_API_RESPONSE' })
  assert.equal(events, 0)
})

test('invalid SSE never reaches callbacks and cancels the owned assistant request', async t => {
  const calls = [], events = []
  mockClient(t, () => new Response(null, { status: 204 }))
  t.mock.method(globalThis, 'fetch', async (url) => {
    if (url.endsWith('/api/auth/csrf')) return new Response(JSON.stringify({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' }))
    calls.push(url)
    if (url.endsWith('/stream')) return new Response('event: delta\ndata: {"text":{"private":"bad"}}\n\n', {
      headers: { 'Content-Type': 'text/event-stream', 'X-Assistant-Request-ID': 'request-1' }
    })
    return new Response(null, { status: 204 })
  })
  await assert.rejects(streamDietAssistant('question', [], null, undefined, (event, data) => events.push([event, data])), { code: 'INVALID_API_RESPONSE' })
  assert.deepEqual(events.map(([event]) => event), ['request'])
  assert.deepEqual(calls, ['/api/assistant/stream', '/api/assistant/requests/request-1'])
})

test('stream fallback JSON and malformed JSON use the same validation error', async t => {
  let body = '{"answer":"valid reply"}'
  mockClient(t, () => new Response(body, { headers: { 'Content-Type': 'application/json' } }))
  assert.equal((await streamDietAssistant('q', [], null, undefined, () => {})).answer, 'valid reply')
  for (body of ['{"answer":{}}', 'not-json']) {
    await assert.rejects(streamDietAssistant('q', [], null, undefined, () => {}), { code: 'INVALID_API_RESPONSE' })
  }
})

test('invalid task status responses stop polling immediately without network retries', async () => {
  let calls = 0
  await assert.rejects(resolveMealAnalysis({ key: 'key', status: 'PROCESSING' }, {
    wait: async () => {}, load: async () => { calls++; throw new ResponseValidationError('task.status') }
  }), { code: 'INVALID_API_RESPONSE' })
  assert.equal(calls, 1)
})

test('analysis submission rejects a response carrying another request key', async t => {
  mockClient(t, () => new Response(JSON.stringify({ key: 'other-request-key', status: 'PENDING' })))
  await assert.rejects(analyzeMeal(new Blob(['image']), 'balanced', 'my-request-key'), { code: 'INVALID_API_RESPONSE' })
})

test('meal fallback status and visible structure roles survive validation and history', () => {
  const foods = [{...record.foods[0], structureRoles:[{role:'protein',source:'VISIBLE_COMPONENT',evidence:'可见炒蛋'}]}]
  const extra = {adviceStatus:'RULE_SERVICE',adviceFailureCode:'MODEL_RATE_LIMITED'}
  const current=parse('/api/meals/analyze',{...analysis,recognition:{foods},...extra})
  assert.equal(current.adviceFailureCode,'MODEL_RATE_LIMITED')
  assert.equal(current.recognition.foods[0].structureRoles[0].evidence,'可见炒蛋')
  const history=parse('/api/meals/1',{...record,foods,...extra})
  assert.equal(history.adviceStatus,'RULE_SERVICE')
})
