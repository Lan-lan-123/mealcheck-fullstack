import { test } from 'node:test'
import assert from 'node:assert/strict'
import { scaleBars } from '../src/utils/barScale.js'
import { resolveWorkspaceRoute } from '../src/utils/workspaceRoute.js'

test('bars show zero as zero and preserve small positive proportions', () => {
  const bars = scaleBars([{ value: 0 }, { value: 1 }, { value: 100 }])
  assert.deepEqual(bars.map(bar => bar.width), [0, 1, 100])
  assert.deepEqual(scaleBars([{ value: .1 }, { value: .5 }]).map(bar => bar.width), [20, 100])
  assert.equal(scaleBars([{ value: 0 }, { value: 0 }])[0].width, 0)
})

test('missing data is not fabricated as zero and signed values share a zero baseline', () => {
  const bars = scaleBars([{ value: null }, { value: '' }, { value: 'bad' }, { value: Infinity }, { value: false }])
  assert.ok(bars.every(bar => bar.value === null && bar.width === 0))
  const signed = scaleBars([{ value: -10 }, { value: 0 }, { value: 30 }])
  assert.deepEqual(signed.map(bar => [bar.left, bar.width]), [[0, 25], [25, 0], [25, 75]])
})

test('workspace links resolve both roles and reject invalid or unauthorized admin pages', () => {
  assert.equal(resolveWorkspaceRoute('/user/records', 'ADMIN').path, '/user/records')
  assert.equal(resolveWorkspaceRoute('/admin/users', 'ADMIN').section, 'users')
  for (const path of ['/admin/users', '/user/unknown', '/user/records/extra', '//user/records']) {
    assert.equal(resolveWorkspaceRoute(path, 'USER').path, '/user/overview')
  }
  assert.equal(resolveWorkspaceRoute('/', 'ADMIN').path, '/admin/overview')
})
