import test from 'node:test'
import assert from 'node:assert/strict'
import { clearSession, getUser, setSession, restoreSession, askDietAssistant, logout } from '../src/api/client.js'

function setup(t) {
  clearSession()
  const storage = globalThis.localStorage, target = globalThis.window
  const values = new Map([['mealcheck_token', 'legacy-secret'], ['mealcheck_user', '{"role":"ADMIN"}']])
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, removeItem: key => values.delete(key) }
  globalThis.window = new EventTarget()
  t.after(() => { clearSession(); globalThis.localStorage = storage; globalThis.window = target })
  return values
}
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const user = { username: 'alice', displayName: 'Alice', role: 'USER' }

test('restoring a cookie session trusts server identity and removes old stored credentials', async t => {
  const values = setup(t)
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/auth/me'); assert.equal(options.credentials, 'include')
    assert.equal(options.headers.Authorization, undefined)
    return json(user)
  })
  assert.deepEqual(await restoreSession(), user)
  assert.equal(getUser().role, 'USER'); assert.equal(values.size, 0)
})

test('authentication dependency failure preserves known state and never treats a 503 as logout', async t => {
  setup(t); setSession(user)
  t.mock.method(globalThis, 'fetch', async () => json({ message: 'unavailable' }, 503))
  await assert.rejects(restoreSession(), error => error.status === 503)
  assert.equal(getUser().username, 'alice')
})

test('CSRF failure does not replay a write and fetches a fresh token for a later explicit retry', async t => {
  setup(t); setSession(user)
  let bootstraps = 0, writes = 0
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url === '/api/auth/csrf') return json({ token: `csrf-${++bootstraps}`, headerName: 'X-XSRF-TOKEN' })
    writes++; assert.equal(options.credentials, 'include')
    assert.equal(options.headers['X-XSRF-TOKEN'], `csrf-${bootstraps}`)
    return writes === 1 ? json({ code: 'CSRF_INVALID', message: 'refresh' }, 403) : json({ answer: 'ok' })
  })
  await assert.rejects(askDietAssistant('q'), error => error.code === 'CSRF_INVALID')
  assert.equal(writes, 1); assert.equal(getUser().username, 'alice')
  assert.equal((await askDietAssistant('q')).answer, 'ok')
  assert.equal(writes, 2); assert.equal(bootstraps, 2)
})

test('failed durable logout preserves state until the server confirms revocation', async t => {
  setup(t); setSession(user)
  let failed = true
  t.mock.method(globalThis, 'fetch', async url => url === '/api/auth/csrf'
    ? json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
    : failed ? json({ code: 'AUTH_DEPENDENCY_UNAVAILABLE' }, 503) : new Response(null, { status: 204 }))
  await assert.rejects(logout(), error => error.status === 503)
  assert.equal(getUser().username, 'alice')
  failed = false; await logout(); assert.equal(getUser(), null)
})
