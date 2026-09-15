package com.example.mealcheck.dto;

import java.util.List;

public class RagBenchmarkResponse {
    private int caseCount;
    private int answerableCaseCount;
    private int noAnswerCaseCount;
    private double baselineHitAt3;
    private double hitAt3;
    private double baselineHitAt5;
    private double hitAt5;
    private double baselineMrr;
    private double mrr;
    private double baselineNdcgAt5;
    private double ndcgAt5;
    private double baselineCategoryAccuracy;
    private double categoryAccuracy;
    private boolean rerankerEnabled;
    private String rerankerProvider;
    private long evaluationDurationMs;
    private double averageRetrievalDurationMs;
    private double averageRerankDurationMs;
    private double averageCandidateCount;
    private double averageResultCount;
    private double emptyResultRate;
    private double baselineNoAnswerAccuracy;
    private double noAnswerAccuracy;
    private double falsePositiveRate;
    private boolean regressionPassed;
    private List<CaseResult> cases;

    public RagBenchmarkResponse() {
    }

    public RagBenchmarkResponse(int caseCount,
                                int answerableCaseCount,
                                int noAnswerCaseCount,
                                double baselineHitAt3,
                                double hitAt3,
                                double baselineHitAt5,
                                double hitAt5,
                                double baselineMrr,
                                double mrr,
                                double baselineNdcgAt5,
                                double ndcgAt5,
                                double baselineCategoryAccuracy,
                                double categoryAccuracy,
                                boolean rerankerEnabled,
                                String rerankerProvider,
                                long evaluationDurationMs,
                                double averageRetrievalDurationMs,
                                double averageRerankDurationMs,
                                double averageCandidateCount,
                                double averageResultCount,
                                double emptyResultRate,
                                double baselineNoAnswerAccuracy,
                                double noAnswerAccuracy,
                                double falsePositiveRate,
                                boolean regressionPassed,
                                List<CaseResult> cases) {
        this.caseCount = caseCount;
        this.answerableCaseCount = answerableCaseCount;
        this.noAnswerCaseCount = noAnswerCaseCount;
        this.baselineHitAt3 = baselineHitAt3;
        this.hitAt3 = hitAt3;
        this.baselineHitAt5 = baselineHitAt5;
        this.hitAt5 = hitAt5;
        this.baselineMrr = baselineMrr;
        this.mrr = mrr;
        this.baselineNdcgAt5 = baselineNdcgAt5;
        this.ndcgAt5 = ndcgAt5;
        this.baselineCategoryAccuracy = baselineCategoryAccuracy;
        this.categoryAccuracy = categoryAccuracy;
        this.rerankerEnabled = rerankerEnabled;
        this.rerankerProvider = rerankerProvider;
        this.evaluationDurationMs = evaluationDurationMs;
        this.averageRetrievalDurationMs = averageRetrievalDurationMs;
        this.averageRerankDurationMs = averageRerankDurationMs;
        this.averageCandidateCount = averageCandidateCount;
        this.averageResultCount = averageResultCount;
        this.emptyResultRate = emptyResultRate;
        this.baselineNoAnswerAccuracy = baselineNoAnswerAccuracy;
        this.noAnswerAccuracy = noAnswerAccuracy;
        this.falsePositiveRate = falsePositiveRate;
        this.regressionPassed = regressionPassed;
        this.cases = cases;
    }

    public int getCaseCount() { return caseCount; }
    public int getAnswerableCaseCount() { return answerableCaseCount; }
    public int getNoAnswerCaseCount() { return noAnswerCaseCount; }
    public double getBaselineHitAt3() { return baselineHitAt3; }
    public double getHitAt3() { return hitAt3; }
    public double getBaselineHitAt5() { return baselineHitAt5; }
    public double getHitAt5() { return hitAt5; }
    public double getBaselineMrr() { return baselineMrr; }
    public double getMrr() { return mrr; }
    public double getBaselineNdcgAt5() { return baselineNdcgAt5; }
    public double getNdcgAt5() { return ndcgAt5; }
    public double getBaselineCategoryAccuracy() { return baselineCategoryAccuracy; }
    public double getCategoryAccuracy() { return categoryAccuracy; }
    public boolean isRerankerEnabled() { return rerankerEnabled; }
    public String getRerankerProvider() { return rerankerProvider; }
    public long getEvaluationDurationMs() { return evaluationDurationMs; }
    public double getAverageRetrievalDurationMs() { return averageRetrievalDurationMs; }
    public double getAverageRerankDurationMs() { return averageRerankDurationMs; }
    public double getAverageCandidateCount() { return averageCandidateCount; }
    public double getAverageResultCount() { return averageResultCount; }
    public double getEmptyResultRate() { return emptyResultRate; }
    public double getBaselineNoAnswerAccuracy() { return baselineNoAnswerAccuracy; }
    public double getNoAnswerAccuracy() { return noAnswerAccuracy; }
    public double getFalsePositiveRate() { return falsePositiveRate; }
    public boolean isRegressionPassed() { return regressionPassed; }
    public List<CaseResult> getCases() { return cases; }

    public static class CaseResult {
        private String question;
        private boolean baselineHitAt3;
        private boolean baselineHitAt5;
        private int baselineFirstRelevantRank;
        private List<String> baselineTopTitles;
        private boolean baselineCategoryHitAt3;
        private boolean hitAt3;
        private boolean hitAt5;
        private double baselineNdcgAt5;
        private double ndcgAt5;
        private int firstRelevantRank;
        private String expectedCategory;
        private boolean categoryHitAt3;
        private String topCategory;
        private List<String> topTitles;
        private long retrievalDurationMs;
        private long rerankDurationMs;
        private int candidateCount;
        private int rerankedCount;
        private int finalResultCount;
        private int thresholdFilteredCount;
        private int duplicateFilteredCount;
        private double effectiveThreshold;
        private boolean expectNoAnswer;
        private boolean baselineNoAnswerCorrect;
        private boolean noAnswerCorrect;
        private double topRoughScore;
        private Double topRerankerRawScore;
        private double topFinalScore;

        public CaseResult() {
        }

        public CaseResult(String question,
                          boolean baselineHitAt3,
                          boolean baselineHitAt5,
                          int baselineFirstRelevantRank,
                          List<String> baselineTopTitles,
                          boolean baselineCategoryHitAt3,
                          boolean hitAt3,
                          boolean hitAt5,
                          int firstRelevantRank,
                          double baselineNdcgAt5,
                          double ndcgAt5,
                          String expectedCategory,
                          boolean categoryHitAt3,
                          String topCategory,
                          List<String> topTitles,
                          long retrievalDurationMs,
                          long rerankDurationMs,
                          int candidateCount,
                          int rerankedCount,
                          int finalResultCount,
                          int thresholdFilteredCount,
                          int duplicateFilteredCount,
                          double effectiveThreshold,
                          boolean expectNoAnswer,
                          boolean baselineNoAnswerCorrect,
                          boolean noAnswerCorrect,
                          double topRoughScore,
                          Double topRerankerRawScore,
                          double topFinalScore) {
            this.question = question;
            this.baselineHitAt3 = baselineHitAt3;
            this.baselineHitAt5 = baselineHitAt5;
            this.baselineFirstRelevantRank = baselineFirstRelevantRank;
            this.baselineTopTitles = baselineTopTitles;
            this.baselineCategoryHitAt3 = baselineCategoryHitAt3;
            this.hitAt3 = hitAt3;
            this.hitAt5 = hitAt5;
            this.firstRelevantRank = firstRelevantRank;
            this.baselineNdcgAt5 = baselineNdcgAt5;
            this.ndcgAt5 = ndcgAt5;
            this.expectedCategory = expectedCategory;
            this.categoryHitAt3 = categoryHitAt3;
            this.topCategory = topCategory;
            this.topTitles = topTitles;
            this.retrievalDurationMs = retrievalDurationMs;
            this.rerankDurationMs = rerankDurationMs;
            this.candidateCount = candidateCount;
            this.rerankedCount = rerankedCount;
            this.finalResultCount = finalResultCount;
            this.thresholdFilteredCount = thresholdFilteredCount;
            this.duplicateFilteredCount = duplicateFilteredCount;
            this.effectiveThreshold = effectiveThreshold;
            this.expectNoAnswer = expectNoAnswer;
            this.baselineNoAnswerCorrect = baselineNoAnswerCorrect;
            this.noAnswerCorrect = noAnswerCorrect;
            this.topRoughScore = topRoughScore;
            this.topRerankerRawScore = topRerankerRawScore;
            this.topFinalScore = topFinalScore;
        }

        public String getQuestion() { return question; }
        public boolean isBaselineHitAt3() { return baselineHitAt3; }
        public boolean isBaselineHitAt5() { return baselineHitAt5; }
        public int getBaselineFirstRelevantRank() { return baselineFirstRelevantRank; }
        public List<String> getBaselineTopTitles() { return baselineTopTitles; }
        public boolean isBaselineCategoryHitAt3() { return baselineCategoryHitAt3; }
        public boolean isHitAt3() { return hitAt3; }
        public boolean isHitAt5() { return hitAt5; }
        public int getFirstRelevantRank() { return firstRelevantRank; }
        public double getBaselineNdcgAt5() { return baselineNdcgAt5; }
        public double getNdcgAt5() { return ndcgAt5; }
        public String getExpectedCategory() { return expectedCategory; }
        public boolean isCategoryHitAt3() { return categoryHitAt3; }
        public String getTopCategory() { return topCategory; }
        public List<String> getTopTitles() { return topTitles; }
        public long getRetrievalDurationMs() { return retrievalDurationMs; }
        public long getRerankDurationMs() { return rerankDurationMs; }
        public int getCandidateCount() { return candidateCount; }
        public int getRerankedCount() { return rerankedCount; }
        public int getFinalResultCount() { return finalResultCount; }
        public int getThresholdFilteredCount() { return thresholdFilteredCount; }
        public int getDuplicateFilteredCount() { return duplicateFilteredCount; }
        public double getEffectiveThreshold() { return effectiveThreshold; }
        public boolean isExpectNoAnswer() { return expectNoAnswer; }
        public boolean isBaselineNoAnswerCorrect() { return baselineNoAnswerCorrect; }
        public boolean isNoAnswerCorrect() { return noAnswerCorrect; }
        public double getTopRoughScore() { return topRoughScore; }
        public Double getTopRerankerRawScore() { return topRerankerRawScore; }
        public double getTopFinalScore() { return topFinalScore; }
    }
}
