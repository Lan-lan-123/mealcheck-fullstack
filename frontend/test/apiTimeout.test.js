import test from 'node:test'
import assert from 'node:assert/strict'
import { askDietAssistant, listMeals, analyzeMeal, requestWeeklyReport } from '../src/api/client.js'

function setup(t) {
  const storage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => null }
  t.after(() => { globalThis.localStorage = storage })
  t.mock.timers.enable({ apis: ['setTimeout'] })
}

test('slow synchronous AI response survives the old 75 second cutoff', async t => {
  setup(t)
  let finish, signal
  t.mock.method(globalThis, 'fetch', (_url, options) => {
    signal = options.signal
    return new Promise((resolve, reject) => {
      finish = () => resolve(new Response(JSON.stringify({ answer: 'completed' })))
      signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), { once: true })
    })
  })
  const response = askDietAssistant('question')
  t.mock.timers.tick(100_000)
  assert.equal(signal.aborted, false)
  finish()
  assert.deepEqual(await response, { answer: 'completed' })
})

test('long AI request stops at 180 seconds and is never automatically resubmitted', async t => {
  setup(t)
  let calls = 0
  t.mock.method(globalThis, 'fetch', (_url, { signal }) => {
    calls++
    return new Promise((_resolve, reject) => signal.addEventListener('abort',
      () => reject(new DOMException('cancelled', 'AbortError')), { once: true }))
  })
  const rejection = assert.rejects(askDietAssistant('question'), error => error.code === 'CLIENT_TIMEOUT')
  t.mock.timers.tick(180_000)
  await rejection
  assert.equal(calls, 1)
})

test('ordinary reads retain a shorter deadline', async t => {
  setup(t)
  t.mock.method(globalThis, 'fetch', (_url, { signal }) => new Promise((_resolve, reject) =>
    signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), { once: true })))
  const rejection = assert.rejects(listMeals(), error => error.code === 'CLIENT_TIMEOUT')
  t.mock.timers.tick(75_000)
  await rejection
})

test('meal and weekly submissions keep using asynchronous job endpoints', async t => {
  setup(t)
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options })
    return new Response(JSON.stringify({ status: 'PENDING' }), { status: 202 })
  })
  await analyzeMeal(new Blob(['image']), 'balanced', 'same-request-key')
  await requestWeeklyReport()
  assert.deepEqual(requests.map(item => item.url), ['/api/meals/analysis-requests', '/api/reports/weekly/jobs?days=7'])
  assert.equal(requests[0].options.headers['Idempotency-Key'], 'same-request-key')
  assert.ok(requests.every(item => item.options.method === 'POST'))
})

test('user cancellation aborts immediately and retains AbortError', async t => {
  setup(t)
  const controller = new AbortController()
  t.mock.method(globalThis, 'fetch', (_url, { signal }) => new Promise((_resolve, reject) =>
    signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), { once: true })))
  const rejection = assert.rejects(askDietAssistant('question', [], null, controller.signal), { name: 'AbortError' })
  controller.abort()
  await rejection
})
