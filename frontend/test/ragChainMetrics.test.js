import test from 'node:test'
import assert from 'node:assert/strict'
import { ragChainMetrics } from '../src/utils/ragChainMetrics.js'

const row = (changes = {}, status = 'PASSED') => ({ status, toolTracePassed: true, ragChain: {
  expectNoAnswer: false, assessmentVersion: 'rag-evidence-v1', annotationStatus: 'PENDING_HUMAN_REVIEW',
  answerability: 'ANSWERABLE', retrievalStatus: 'PASSED', retrievalPassed: true,
  retrievals: [{ status: 'SUCCESS' }], hitAt3: true, hitAt5: true, reciprocalRank: 1, ndcgAt5: 1,
  finalEvidenceCovered: true, recoveredBySupplement: false, ...changes,
} })

test('first ranking, final coverage and recovered evidence use separate denominators', () => {
  const result = ragChainMetrics([
    row({ retrievals: [], retrievalStatus: 'NOT_CALLED', retrievalPassed: false, finalEvidenceCovered: false }, 'FAILED'),
    row({ hitAt3: false, hitAt5: false, reciprocalRank: 0, ndcgAt5: 0, recoveredBySupplement: true }),
    row({ finalEvidenceCovered: false, retrievalStatus: 'FAILED', retrievalPassed: false, answerability: 'INSUFFICIENT_EVIDENCE' }, 'FAILED'),
    row({ retrievals: [{ status: 'ERROR' }], retrievalStatus: 'ERROR', finalEvidenceCovered: false, retrievalPassed: false }, 'ERROR'),
    row({ expectNoAnswer: true, answerability: 'OUT_OF_SCOPE', retrievals: [], retrievalStatus: 'NOT_REQUIRED', finalEvidenceCovered: false }),
  ])
  assert.equal(result.answerableCount, 4)
  assert.equal(result.executedCount, 2)
  assert.equal(result.firstHitAt3, .5)
  assert.equal(result.finalMeasuredCount, 4)
  assert.equal(result.finalCoveredCount, 1)
  assert.equal(result.recoveredCount, 1)
  assert.equal(result.notCalledCount, 1)
  assert.equal(result.retrievalErrorCount, 1)
  assert.equal(result.boundaryCorrectCount, 1)
  assert.equal(result.insufficientEvidenceCount, 1)
  assert.equal(result.pendingReviewCount, 5)
})

test('old reports do not fabricate final coverage and an empty run has no ranking score', () => {
  const legacy = ragChainMetrics([row({ assessmentVersion: null, finalEvidenceCovered: false })])
  assert.equal(legacy.finalMeasuredCount, 0)
  const empty = ragChainMetrics([])
  assert.equal(empty.firstHitAt3, null)
  assert.equal(empty.firstMrr, null)
})

test('later execution failure retains evidence but does not count as a successful repair or boundary pass', () => {
  const result = ragChainMetrics([
    row({ recoveredBySupplement: true }, 'ERROR'),
    row({ expectNoAnswer: true, retrievals: [], retrievalStatus: 'NOT_REQUIRED' }, 'ERROR'),
  ])
  assert.equal(result.finalCoveredCount, 1)
  assert.equal(result.recoveredCount, 0)
  assert.equal(result.boundaryCorrectCount, 0)
})

test('unjudged hits are separate from misses and cannot silently lower ranking averages', () => {
  const result = ragChainMetrics([
    row({ judgmentCoverage: { primaryRankingComplete: false, totalUnjudgedCount: 2 }, retrievalStatus: 'UNJUDGED', finalEvidenceCovered: false }),
    row({ judgmentCoverage: { primaryRankingComplete: true, totalUnjudgedCount: 0 }, hitAt3: false, hitAt5: false, reciprocalRank: 0, ndcgAt5: 0 }),
  ])
  assert.equal(result.executedCount, 2)
  assert.equal(result.rankingMeasuredCount, 1)
  assert.equal(result.rankingPendingCount, 1)
  assert.equal(result.finalPendingCount, 1)
  assert.equal(result.unjudgedHitCount, 2)
  assert.equal(result.firstHitAt3, 0)
  assert.equal(result.finalMeasuredCount, 1)
})

test('confirmed coverage with an unjudged result remains covered while ranking stays pending', () => {
  const result = ragChainMetrics([row({ judgmentCoverage: { primaryRankingComplete: false, totalUnjudgedCount: 1 } })])
  assert.equal(result.finalCoveredCount, 1)
  assert.equal(result.finalPendingCount, 0)
  assert.equal(result.rankingMeasuredCount, 0)
  assert.equal(result.firstNdcg, null)
})
