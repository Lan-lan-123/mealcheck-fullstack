import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequestScope } from '../src/utils/requestScope.js'
import { reportPeriod } from '../src/utils/reportPeriod.js'
import { resolveWeeklyReportJob } from '../src/utils/weeklyReportJob.js'

test('switching conversations or filters rejects late responses', () => {
  const scope = createRequestScope(), first = scope.begin(), second = scope.begin()
  assert.equal(first(), false); assert.equal(second(), true)
  scope.invalidate(); assert.equal(second(), false)
})
test('report dates are inclusive server dates and independent of current browser date', () => {
  assert.deepEqual(reportPeriod({ periodStart: '2025-01-01', periodEnd: '2025-01-07' }), { from: '2025-01-01', to: '2025-01-07' })
  assert.throws(() => reportPeriod({ periodStart: '2025-01-07', periodEnd: '2025-01-01' }))
  assert.throws(() => reportPeriod({}))
})
test('invalidated report gives an actionable resubmission message', async () => {
  await assert.rejects(resolveWeeklyReportJob({ status: 'STALE' }, {}), /旧周报已失效/)
})
