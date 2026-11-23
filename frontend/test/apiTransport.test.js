import test from 'node:test'
import assert from 'node:assert/strict'
import { askDietAssistant, streamDietAssistant, clearSession } from '../src/api/client.js'

for (const status of [404, 405]) {
  for (const reviewMode of ['FAST', 'REVIEWED', undefined]) {
    test(`SSE ${status} fallback preserves ${reviewMode ?? 'default REVIEWED'} mode and request context`, async t => {
      clearSession()
      t.after(clearSession)
      const calls = []
      t.mock.method(globalThis, 'fetch', async (url, options) => {
        if (url === '/api/auth/csrf') return new Response(JSON.stringify({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
        calls.push({ url, body: JSON.parse(options.body) })
        if (url === '/api/assistant/stream') return new Response(null, { status })
        assert.equal(url, '/api/assistant/ask')
        return new Response(JSON.stringify({ answer: 'completed' }))
      })

      const history = [{ role: 'user', text: '上一问' }]
      const result = await streamDietAssistant('当前问题', history, 42, undefined, () => {}, reviewMode)
      assert.equal(result.answer, 'completed')
      const body = { question: '当前问题', history, conversationId: 42, reviewMode: reviewMode ?? 'REVIEWED' }
      assert.deepEqual(calls, [
        { url: '/api/assistant/stream', body },
        { url: '/api/assistant/ask', body }
      ])
    })
  }
}

test('HTTP and streaming requests share CSRF bootstrap while cancellation remains per request', async t => {
  clearSession()
  t.after(clearSession)
  let releaseCsrf
  let bootstraps = 0
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/auth/csrf') {
      bootstraps++
      return new Promise(resolve => { releaseCsrf = () => resolve(new Response(JSON.stringify({ token: 'shared', headerName: 'X-XSRF-TOKEN' }))) })
    }
    requests.push(url)
    assert.equal(options.credentials, 'include')
    assert.equal(options.headers['X-XSRF-TOKEN'], 'shared')
    return new Response(JSON.stringify({ answer: 'completed' }), { headers: { 'Content-Type': 'application/json' } })
  })

  const controller = new AbortController()
  const cancelled = assert.rejects(askDietAssistant('cancelled', [], null, controller.signal), { name: 'AbortError' })
  const streaming = streamDietAssistant('keep waiting', [], null, undefined, () => {})
  assert.equal(bootstraps, 1)
  controller.abort()
  await cancelled
  releaseCsrf()
  assert.equal((await streaming).answer, 'completed')
  assert.deepEqual(requests, ['/api/assistant/stream'])
})
