import test from 'node:test'
import assert from 'node:assert/strict'
import { clearSession, getUser, setSession, streamDietAssistant } from '../src/api/client.js'

function setup(t) {
  clearSession()
  const previousStorage = globalThis.localStorage
  const previousWindow = globalThis.window
  const values = new Map()
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  }
  globalThis.window = new EventTarget()
  t.after(() => {
    if (previousStorage === undefined) delete globalThis.localStorage
    else globalThis.localStorage = previousStorage
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  })

  let expiredEvents = 0
  window.addEventListener('mealcheck:session-expired', () => expiredEvents++)
  let finish, authorization, credentials, begin
  const started = new Promise(resolve => { begin = resolve })
  t.mock.method(globalThis, 'fetch', (url, options) => {
    if (url === '/api/auth/csrf') return Promise.resolve(new Response(JSON.stringify({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' })))
    assert.equal(url, '/api/assistant/stream')
    authorization = options.headers.Authorization
    credentials = options.credentials
    assert.equal(options.headers['X-XSRF-TOKEN'], 'csrf-test')
    begin()
    return new Promise(resolve => { finish = resolve })
  })

  return {
    expiredEvents: () => expiredEvents,
    authorization: () => authorization,
    credentials: () => credentials,
    started,
    rejectRequest: () => finish(new Response(JSON.stringify({ message: 'expired' }), {
      status: 401, headers: { 'Content-Type': 'application/json' }
    }))
  }
}

const newSession = { token: 'new-token', username: 'new-user', role: 'USER' }

for (const originalToken of ['old-token', null]) {
  test(`late streaming 401 preserves a new session after ${originalToken ? 'an old authenticated' : 'an unauthenticated'} request`, async t => {
    const state = setup(t)
    if (originalToken) setSession({ token: originalToken, username: 'old-user', role: 'USER' })
    const pending = streamDietAssistant('question', [], null, undefined, () => {})
    const rejection = assert.rejects(pending, error => error.status === 401)
    await state.started
    assert.equal(state.authorization(), undefined)
    assert.equal(state.credentials(), 'include')

    setSession(newSession)
    state.rejectRequest()
    await rejection

    assert.equal(localStorage.getItem('mealcheck_token'), null)
    assert.equal(getUser().username, newSession.username)
    assert.equal(state.expiredEvents(), 0)
  })
}

test('streaming 401 clears the current invalid session and emits one expiry event', async t => {
  const state = setup(t)
  setSession(newSession)
  const rejection = assert.rejects(
    streamDietAssistant('question', [], null, undefined, () => {}),
    error => error.status === 401
  )
  await state.started
  state.rejectRequest()
  await rejection

  assert.equal(localStorage.getItem('mealcheck_token'), null)
  assert.equal(getUser(), null)
  assert.equal(state.expiredEvents(), 1)
})
