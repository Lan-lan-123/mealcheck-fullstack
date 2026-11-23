import test from 'node:test'
import assert from 'node:assert/strict'
import { assistantEvent, conversationDetail, ResponseValidationError } from '../src/api/validation.js'
import { clearSession, streamDietAssistant } from '../src/api/client.js'

const call = { name: 'recommend_next_meal', arguments: '{"mealType":"DINNER","scene":"HOME","budget":25,"availableMinutes":20}',
  origin: 'MODEL', status: 'SUCCESS', round: 1, durationMs: 12, resultSummary: '找到 3 套搭配' }

test('SSE completion and restored conversation preserve actual tool evidence', async t => {
  const complete = { answer: '以下为实际搭配', conversationId: 7, toolCalls: [call] }
  assert.deepEqual(assistantEvent('complete', complete).toolCalls, [call])
  assert.deepEqual(conversationDetail({ id: 7, messages: [{ role: 'assistant', text: complete.answer, toolCalls: [call] }] }, 'history').messages[0].toolCalls, [call])
  clearSession()
  const previousStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => null }
  t.after(() => { globalThis.localStorage = previousStorage })
  t.mock.method(globalThis, 'fetch', async url => url.endsWith('/api/auth/csrf')
    ? new Response(JSON.stringify({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' }))
    : new Response(`event: complete\ndata: ${JSON.stringify(complete)}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } }))
  const result = await streamDietAssistant('推荐晚餐搭配', [], null, undefined, () => {})
  assert.deepEqual(result.toolCalls, [call])
})

test('invalid call metadata fails validation instead of displaying misleading success', () => {
  for (const change of [{ origin: 'FAKE' }, { durationMs: -1 }, { round: 0 }, { arguments: {} }]) {
    assert.throws(() => assistantEvent('complete', { answer: '完成', toolCalls: [{ ...call, ...change }] }), ResponseValidationError)
  }
  assert.equal(assistantEvent('complete', { answer: '没有调用工具' }).toolCalls, undefined)
})
