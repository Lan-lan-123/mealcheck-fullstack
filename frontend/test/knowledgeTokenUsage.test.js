import test from 'node:test'
import assert from 'node:assert/strict'
import { embeddingUsageLabel } from '../src/utils/knowledgeTokenUsage.js'

test('distinguishes reported input, total-only usage, zero and unreported values', () => {
  assert.equal(embeddingUsageLabel(22, 22), '实际输入 22 tokens')
  assert.equal(embeddingUsageLabel(null, 17), '接口总用量 17 tokens')
  assert.equal(embeddingUsageLabel(0, 0), '实际输入 0 tokens')
  for (const value of [null, undefined, -1, 1.5, '22', Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(embeddingUsageLabel(value, null), '接口用量未记录')
  }
})
