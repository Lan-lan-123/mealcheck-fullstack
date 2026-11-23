import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveMealAnalysis } from '../src/utils/mealAnalysisJob.js'
import { resolveWeeklyReportJob } from '../src/utils/weeklyReportJob.js'

const variants = [
  { name: 'meal', resolve: resolveMealAnalysis, initial: { key: 'original-key', status: 'PENDING' }, running: 'PROCESSING', key: 'original-key', resultField: 'result' },
  { name: 'weekly', resolve: resolveWeeklyReportJob, initial: { id: 42, status: 'PENDING' }, running: 'RUNNING', key: 42, resultField: 'report' }
]
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
class Visibility extends EventTarget {
  hidden = false
  listeners = 0
  addEventListener(...args) { this.listeners++; super.addEventListener(...args) }
  removeEventListener(...args) { this.listeners--; super.removeEventListener(...args) }
  set(hidden) { this.hidden = hidden; this.dispatchEvent(new Event('visibilitychange')) }
}

for (const v of variants) {
  const succeeded = { ...v.initial, status: 'SUCCEEDED', [v.resultField]: { complete: true } }
  test(`${v.name}: unchanged status backs off with jitter and progress resets the delay`, async () => {
    const waits = [], keys = []
    const states = [v.initial, v.initial, { ...v.initial, status: v.running }, succeeded]
    await v.resolve(v.initial, {
      random: () => 0.5, wait: async ms => waits.push(ms),
      load: async key => { keys.push(key); return states.shift() }
    })
    assert.deepEqual(waits, [3125, 4625, 6875, 3125])
    assert.deepEqual(keys, Array(4).fill(v.key))
  })

  test(`${v.name}: hidden tabs slow down and returning visible immediately reads without overlap`, async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const visibility = new Visibility(), controller = new AbortController()
    let reads = 0, completeRead, inFlight = 0, maxInFlight = 0
    const task = v.resolve(v.initial, {
      signal: controller.signal, visibility, random: () => 0,
      load: async key => {
        assert.equal(key, v.key)
        reads++; inFlight++; maxInFlight = Math.max(maxInFlight, inFlight)
        const result = await new Promise(resolve => { completeRead = resolve })
        inFlight--; return result
      }
    })
    t.mock.timers.tick(1000)
    visibility.set(true)
    await flush()
    t.mock.timers.tick(29000)
    await flush()
    assert.equal(reads, 0)
    t.mock.timers.tick(1000)
    await flush()
    assert.equal(reads, 1)
    visibility.set(false)
    await flush()
    assert.equal(reads, 1)
    completeRead(v.initial)
    await flush()
    assert.equal(reads, 2) // Visibility was restored during the first request: no extra delay.
    completeRead(succeeded)
    await task
    assert.equal(maxInFlight, 1)
    assert.equal(visibility.listeners, 0)
  })

  test(`${v.name}: returning visible cancels a hidden timer and queries immediately`, async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const visibility = new Visibility(); visibility.hidden = true
    let reads = 0
    const task = v.resolve(v.initial, { visibility, load: async () => { reads++; return succeeded } })
    t.mock.timers.tick(2000)
    await flush()
    assert.equal(reads, 0)
    visibility.set(false)
    await task
    assert.equal(reads, 1)
    assert.equal(visibility.listeners, 0)
    t.mock.timers.tick(60000)
    await flush()
    assert.equal(reads, 1)
  })

  test(`${v.name}: temporary read failures retry the same task and clear the connection warning`, async () => {
    const waits = [], warnings = [], keys = []
    let reads = 0
    await v.resolve(v.initial, {
      random: () => 0, wait: async ms => waits.push(ms), onUpdate: state => warnings.push(!!state.connectionInterrupted),
      load: async key => {
        keys.push(key)
        if (++reads === 1) throw Object.assign(new Error('busy'), { status: 429, retryAfter: 12 })
        if (reads === 2) return v.initial
        return succeeded
      }
    })
    assert.deepEqual(waits, [3000, 12000, 3000])
    assert.deepEqual(warnings, [false, true, false, false])
    assert.deepEqual(keys, Array(3).fill(v.key))
  })

  test(`${v.name}: invalid contracts and permanent HTTP errors stop immediately; offline retries are bounded`, async () => {
    for (const error of [Object.assign(new Error(), { code: 'INVALID_API_RESPONSE' }), ...[400, 401, 403, 404, 409, 410, 422].map(status => Object.assign(new Error(), { status }))]) {
      let reads = 0
      await assert.rejects(v.resolve(v.initial, { wait: async () => {}, load: async () => { reads++; throw error } }), e => e === error)
      assert.equal(reads, 1)
    }
    let reads = 0
    await assert.rejects(v.resolve(v.initial, { wait: async () => {}, load: async () => { reads++; throw new TypeError('offline') } }), /offline/)
    assert.equal(reads, 5)
  })

  test(`${v.name}: cancellation fences a request that ignores its signal and removes visibility listeners`, async () => {
    const controller = new AbortController(), visibility = new Visibility(), updates = []
    let finish, requestSignal
    const task = v.resolve(v.initial, {
      signal: controller.signal, visibility, wait: async () => {}, onUpdate: state => updates.push(state.status),
      load: (_key, signal) => { requestSignal = signal; return new Promise(resolve => { finish = resolve }) }
    })
    await flush()
    controller.abort()
    await assert.rejects(task, { name: 'AbortError' })
    assert.equal(requestSignal.aborted, true)
    finish(succeeded)
    await flush()
    assert.deepEqual(updates, ['PENDING'])
    assert.equal(visibility.listeners, 0)
  })

  test(`${v.name}: total deadline cancels an in-flight read and preserves task recovery guidance`, async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    let requestSignal, finish
    const task = v.resolve(v.initial, {
      maxDurationMs: 4000, random: () => 0,
      load: (_key, signal) => { requestSignal = signal; return new Promise(resolve => { finish = resolve }) }
    })
    t.mock.timers.tick(3000)
    await flush()
    assert.equal(requestSignal.aborted, false)
    const rejection = assert.rejects(task, error => error.code === 'POLLING_TIMEOUT' && /后台处理中/.test(error.message))
    t.mock.timers.tick(1000)
    await rejection
    assert.equal(requestSignal.aborted, true)
    finish(succeeded)
    await flush()
  })
}

test('server polling hint is a lower bound and foreground backoff is capped', async () => {
  const waits = []
  let reads = 0
  await resolveMealAnalysis({ key: 'same', status: 'PENDING', retryAfterSeconds: 2 }, {
    wait: async ms => waits.push(ms), random: () => 0.99,
    load: async () => ++reads === 10 ? { status: 'SUCCEEDED', result: 1 } : { status: 'PENDING', retryAfterSeconds: 2 }
  })
  assert.ok(waits.every(ms => ms >= 2000 && ms <= 10000))
  assert.equal(waits.at(-1), 10000)
})
