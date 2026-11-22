package com.example.mealcheck.dto;

import java.util.List;

/** Evidence from the searches actually executed inside one assistant evaluation case. */
public record RagChainReport(boolean expectNoAnswer, List<String> expectedTitleKeywords,
                             String retrievalStatus, boolean retrievalPassed,
                             int firstRelevantRank, boolean hitAt3, boolean hitAt5,
                             double reciprocalRank, double ndcgAt5, List<Retrieval> retrievals,
                             String assessmentVersion, String answerability, String annotationStatus,
                             boolean finalEvidenceCovered, double finalEvidenceCoverage,
                             boolean recoveredBySupplement, List<String> matchedEvidenceIds,
                             List<EvidenceTarget> expectedEvidence, JudgmentCoverage judgmentCoverage) {
    public RagChainReport(boolean expectNoAnswer, List<String> expectedTitleKeywords,
                          String retrievalStatus, boolean retrievalPassed, int firstRelevantRank,
                          boolean hitAt3, boolean hitAt5, double reciprocalRank, double ndcgAt5,
                          List<Retrieval> retrievals, String assessmentVersion, String answerability,
                          String annotationStatus, boolean finalEvidenceCovered, double finalEvidenceCoverage,
                          boolean recoveredBySupplement, List<String> matchedEvidenceIds, List<EvidenceTarget> expectedEvidence) {
        this(expectNoAnswer, expectedTitleKeywords, retrievalStatus, retrievalPassed, firstRelevantRank,
                hitAt3, hitAt5, reciprocalRank, ndcgAt5, retrievals, assessmentVersion, answerability,
                annotationStatus, finalEvidenceCovered, finalEvidenceCoverage, recoveredBySupplement,
                matchedEvidenceIds, expectedEvidence, null);
    }
    public RagChainReport(boolean expectNoAnswer, List<String> expectedTitleKeywords,
                          String retrievalStatus, boolean retrievalPassed, int firstRelevantRank,
                          boolean hitAt3, boolean hitAt5, double reciprocalRank, double ndcgAt5, List<Retrieval> retrievals) {
        this(expectNoAnswer, expectedTitleKeywords, retrievalStatus, retrievalPassed, firstRelevantRank,
                hitAt3, hitAt5, reciprocalRank, ndcgAt5, retrievals, null, null, null, false, 0, false, List.of(), List.of());
    }
    public RagChainReport {
        expectedTitleKeywords = expectedTitleKeywords == null ? List.of() : List.copyOf(expectedTitleKeywords);
        retrievals = retrievals == null ? List.of() : List.copyOf(retrievals);
        matchedEvidenceIds = matchedEvidenceIds == null ? List.of() : List.copyOf(matchedEvidenceIds);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
    }
    public record EvidenceTarget(String knowledgeId, String title, int relevance, List<String> evidenceQuotes, String rationale) {
        public EvidenceTarget { evidenceQuotes = evidenceQuotes == null ? List.of() : List.copyOf(evidenceQuotes); }
    }
    /** Counts retrieval occurrences; the same chunk in a supplementary search is counted again. */
    public record JudgmentCoverage(int primaryHitCount, int primaryUnjudgedCount, int totalHitCount,
                                   int totalUnjudgedCount, int invalidContentCount,
                                   boolean primaryRankingComplete) { }
    public record Hit(Long id, String title, String category, double score, String knowledgeId, String content) {
        public Hit(Long id, String title, String category, double score) { this(id, title, category, score, null, null); }
    }
    public record Retrieval(String toolCallId, String query, int requestedLimit, String status,
                            long durationMs, List<Hit> hits, String errorType) {
        public Retrieval { hits = hits == null ? List.of() : List.copyOf(hits); }
    }
}
