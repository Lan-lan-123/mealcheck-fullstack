import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveWeeklyReportJob, waitForPoll } from '../src/utils/weeklyReportJob.js'

test('queued and retrying jobs reuse their ID and return the completed report', async () => {
  const states = [], ids = [], report = { averageScore: 80 }
  const queue = [{ id: 4, status: 'RETRY' }, { id: 4, status: 'SUCCEEDED', report }]
  const result = await resolveWeeklyReportJob({ id: 4, status: 'PENDING' }, {
    load: async id => { ids.push(id); return queue.shift() }, wait: async () => {},
    onUpdate: job => states.push(job.status)
  })
  assert.equal(result, report)
  assert.deepEqual(ids, [4, 4])
  assert.deepEqual(states, ['PENDING', 'RETRY', 'SUCCEEDED'])
})

test('a cached completed report does not poll', async () => {
  assert.deepEqual(await resolveWeeklyReportJob({ status: 'SUCCEEDED', report: { days: 7 } }, {
    load: () => assert.fail('should not poll')
  }), { days: 7 })
})

test('dead jobs and missing results produce actionable errors', async () => {
  await assert.rejects(resolveWeeklyReportJob({ status: 'DEAD' }, {}), /停止自动重试/)
  await assert.rejects(resolveWeeklyReportJob({ status: 'SUCCEEDED' }, {}), /结果暂时不可用/)
})

test('polling is bounded without submitting duplicate jobs', async () => {
  await assert.rejects(resolveWeeklyReportJob({ id: 9, status: 'RUNNING' }, {
    maxPolls: 0, load: () => assert.fail('should not poll')
  }), /后台处理中/)
})

test('unmount cancellation stops a pending poll immediately', async () => {
  const controller = new AbortController()
  const promise = waitForPoll(60_000, controller.signal)
  controller.abort()
  await assert.rejects(promise, { name: 'AbortError' })
  await assert.rejects(resolveWeeklyReportJob({ status: 'RUNNING' }, {
    signal: controller.signal, load: () => assert.fail('should not poll')
  }), { name: 'AbortError' })
})
