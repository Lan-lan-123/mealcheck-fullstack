export function ragChainMetrics(cases = []) {
  const rows = cases.filter(row => row.ragChain)
  const answerable = rows.filter(row => !row.ragChain.expectNoAnswer)
  const executed = answerable.filter(row => row.ragChain.retrievals?.[0]?.status === 'SUCCESS')
  const rankingMeasured = executed.filter(row => row.ragChain.judgmentCoverage?.primaryRankingComplete !== false)
  // A historical report without a scoring version did not measure final coverage.
  const finalMeasured = answerable.filter(row => row.ragChain.assessmentVersion && row.ragChain.retrievalStatus !== 'UNJUDGED')
  const boundary = rows.filter(row => row.ragChain.expectNoAnswer)
  return {
    answerableCount: answerable.length,
    executedCount: executed.length,
    rankingMeasuredCount: rankingMeasured.length,
    rankingPendingCount: executed.length - rankingMeasured.length,
    finalPendingCount: answerable.filter(row => row.ragChain.retrievalStatus === 'UNJUDGED').length,
    unjudgedHitCount: rows.reduce((sum, row) => sum + (row.ragChain.judgmentCoverage?.totalUnjudgedCount || 0), 0),
    firstHitAt3: rankingMeasured.length ? rankingMeasured.filter(row => row.ragChain.hitAt3).length / rankingMeasured.length : null,
    firstHitAt5: rankingMeasured.length ? rankingMeasured.filter(row => row.ragChain.hitAt5).length / rankingMeasured.length : null,
    firstMrr: rankingMeasured.length ? rankingMeasured.reduce((sum, row) => sum + (row.ragChain.reciprocalRank || 0), 0) / rankingMeasured.length : null,
    firstNdcg: rankingMeasured.length ? rankingMeasured.reduce((sum, row) => sum + (row.ragChain.ndcgAt5 || 0), 0) / rankingMeasured.length : null,
    notCalledCount: answerable.filter(row => row.ragChain.retrievalStatus === 'NOT_CALLED').length,
    retrievalErrorCount: rows.filter(row => row.ragChain.retrievalStatus === 'ERROR').length,
    finalMeasuredCount: finalMeasured.length,
    finalCoveredCount: finalMeasured.filter(row => row.ragChain.finalEvidenceCovered).length,
    recoveredCount: finalMeasured.filter(row => row.status !== 'ERROR' && row.ragChain.retrievalPassed && row.ragChain.recoveredBySupplement).length,
    insufficientEvidenceCount: rows.filter(row => row.ragChain.answerability === 'INSUFFICIENT_EVIDENCE').length,
    pendingReviewCount: rows.filter(row => row.ragChain.annotationStatus === 'PENDING_HUMAN_REVIEW').length,
    boundaryCount: boundary.length,
    boundaryCorrectCount: boundary.filter(row => row.status !== 'ERROR' && row.toolTracePassed && row.ragChain.retrievalStatus === 'NOT_REQUIRED').length,
  }
}
