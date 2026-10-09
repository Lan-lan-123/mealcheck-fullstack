import { test } from 'node:test'
import assert from 'node:assert/strict'
import { formatTime } from '../src/utils/time.js'

test('timestamps are displayed in Shanghai independently of the browser zone', () => {
  const before = process.env.TZ
  try {
    process.env.TZ = 'America/Los_Angeles'
    const west = formatTime('2026-10-08T16:05:00Z')
    process.env.TZ = 'UTC'
    assert.equal(formatTime('2026-10-08T16:05:00Z'), west)
    assert.match(west, /2026\/10\/9.*00:05:00/)
    assert.equal(formatTime('2026-10-09T00:05:00+08:00'), west)
    assert.equal(formatTime('2026-10-08T16:05:00'), west)
  } finally {
    if (before === undefined) delete process.env.TZ
    else process.env.TZ = before
  }
})

test('business dates stay dates and invalid timestamps are not displayed', () => {
  assert.equal(formatTime('2026-10-08'), '2026-10-08')
  assert.equal(formatTime(null), '-')
  assert.equal(formatTime('invalid'), '-')
})
