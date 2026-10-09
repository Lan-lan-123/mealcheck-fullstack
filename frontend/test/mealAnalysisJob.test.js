import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveMealAnalysis, submitMealAnalysisWithRetry } from '../src/utils/mealAnalysisJob.js'

test('busy admission retries keep the original payload and key, respect Retry-After and add jitter', async () => {
  const file = {}, calls = [], waits = []
  const result = await submitMealAnalysisWithRetry(file, 'current', 'same-key', {
    submit: async (...args) => { calls.push(args); if (calls.length < 3) throw Object.assign(new Error('busy'), { status: 429, code: 'UPLOAD_BUSY', retryAfter: 3 }); return { status: 'PENDING' } },
    wait: async value => waits.push(value), random: () => 0.5
  })
  assert.equal(result.status, 'PENDING')
  assert.deepEqual(waits, [3125, 3125])
  assert.ok(calls.every(args => args[0] === file && args[1] === 'current' && args[2] === 'same-key'))
})

test('only admission rejection is retried and retries have a limit', async () => {
  for (const error of [Object.assign(new Error(), { status: 500 }), Object.assign(new Error(), { status: 429, code: 'ANALYSIS_USER_BUSY' }), Object.assign(new Error(), { status: 429, code: 'RATE_LIMIT_EXCEEDED' }), Object.assign(new Error(), { status: 408 }), Object.assign(new Error(), { status: 409 })]) {
    let calls = 0
    await assert.rejects(submitMealAnalysisWithRetry({}, 'current', 'key', { submit: async () => { calls++; throw error }, wait: async () => {} }), value => value === error)
    assert.equal(calls, 1)
  }
  let calls = 0
  await assert.rejects(submitMealAnalysisWithRetry({}, 'current', 'key', { submit: async () => { calls++; throw Object.assign(new Error('busy'), { status: 429, code: 'UPLOAD_BUSY' }) }, wait: async () => {} }), /busy/)
  assert.equal(calls, 3)
})

test('global backlog rejection can retry safely using the original key', async () => {
  const calls=[];
  const result=await submitMealAnalysisWithRetry({},'current','original-key',{
    submit:async (...args)=>{calls.push(args);if(calls.length===1) throw Object.assign(new Error('busy'),{status:429,code:'ANALYSIS_BUSY',retryAfter:3});return {status:'PENDING'}},
    wait:async ()=>{}
  });
  assert.equal(result.status,'PENDING');assert.deepEqual(calls.map(args=>args[2]),['original-key','original-key']);
})

test('aborting admission backoff prevents another upload', async () => {
  const controller = new AbortController()
  let calls = 0
  await assert.rejects(submitMealAnalysisWithRetry({}, 'current', 'key', {
    signal: controller.signal, submit: async () => { calls++; throw Object.assign(new Error(), { status: 429, code: 'UPLOAD_BUSY' }) },
    wait: async () => controller.abort()
  }), error => error.name === 'AbortError')
  assert.equal(calls, 1)
})

test('queued analysis automatically polls the same key through completion', async () => {
  const states = [{ key: 'key', status: 'PROCESSING' }, { key: 'key', status: 'SUCCEEDED', result: { recordId: 9 } }]
  const keys = [], updates = []
  const result = await resolveMealAnalysis({ key: 'key', status: 'PENDING' }, {
    load: async key => { keys.push(key); return states.shift() }, wait: async () => {}, onUpdate: state => updates.push(state.status)
  })
  assert.deepEqual(result, { recordId: 9 }); assert.deepEqual(keys, ['key', 'key']); assert.deepEqual(updates, ['PENDING', 'PROCESSING', 'SUCCEEDED'])
})
test('network interruptions retry status reads without another submission', async () => {
  let reads = 0
  const result = await resolveMealAnalysis({ key: 'key', status: 'PROCESSING' }, {
    load: async () => { if (++reads < 3) throw new Error('offline'); return { status: 'SUCCEEDED', result: 1 } }, wait: async () => {}
  })
  assert.equal(reads, 3); assert.equal(result, 1)
})
test('failed, unknown, expired and excessively long tasks stop polling with retry guidance', async () => {
  for (const status of ['FAILED', 'RETRYABLE', 'unknown']) await assert.rejects(resolveMealAnalysis({ status }, { load: async () => {} }))
  await assert.rejects(resolveMealAnalysis({ key: 'key', status: 'PENDING' }, { load: async () => {}, maxPolls: 0 }), /后台/)
})
test('aborted navigation cannot publish a late analysis result', async () => {
  const controller = new AbortController()
  await assert.rejects(resolveMealAnalysis({ key: 'key', status: 'PENDING' }, {
    signal: controller.signal, wait: async () => controller.abort(), load: async () => ({ status: 'SUCCEEDED', result: 1 })
  }), error => error.name === 'AbortError')
})

test('budget failures have actionable messages and keep their reason', async () => {
  for (const [code, message] of [['AI_BUDGET_EXCEEDED', /重置/], ['AI_BUDGET_UNAVAILABLE', /稍后/]]) {
    await assert.rejects(resolveMealAnalysis({ status: 'FAILED', errorCode: code }, { load: async () => {} }), error => error.code === code && message.test(error.message))
  }
})
