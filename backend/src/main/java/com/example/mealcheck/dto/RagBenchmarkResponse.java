package com.example.mealcheck.dto;

import java.util.List;

public class RagBenchmarkResponse {
    private int caseCount;
    private double hitAt3;
    private double mrr;
    private double categoryAccuracy;
    private List<CaseResult> cases;

    public RagBenchmarkResponse() {
    }

    public RagBenchmarkResponse(int caseCount,
                                double hitAt3,
                                double mrr,
                                double categoryAccuracy,
                                List<CaseResult> cases) {
        this.caseCount = caseCount;
        this.hitAt3 = hitAt3;
        this.mrr = mrr;
        this.categoryAccuracy = categoryAccuracy;
        this.cases = cases;
    }

    public int getCaseCount() { return caseCount; }
    public double getHitAt3() { return hitAt3; }
    public double getMrr() { return mrr; }
    public double getCategoryAccuracy() { return categoryAccuracy; }
    public List<CaseResult> getCases() { return cases; }

    public static class CaseResult {
        private String question;
        private boolean hitAt3;
        private int firstRelevantRank;
        private String expectedCategory;
        private boolean categoryHitAt3;
        private String topCategory;
        private List<String> topTitles;

        public CaseResult() {
        }

        public CaseResult(String question,
                          boolean hitAt3,
                          int firstRelevantRank,
                          String expectedCategory,
                          boolean categoryHitAt3,
                          String topCategory,
                          List<String> topTitles) {
            this.question = question;
            this.hitAt3 = hitAt3;
            this.firstRelevantRank = firstRelevantRank;
            this.expectedCategory = expectedCategory;
            this.categoryHitAt3 = categoryHitAt3;
            this.topCategory = topCategory;
            this.topTitles = topTitles;
        }

        public String getQuestion() { return question; }
        public boolean isHitAt3() { return hitAt3; }
        public int getFirstRelevantRank() { return firstRelevantRank; }
        public String getExpectedCategory() { return expectedCategory; }
        public boolean isCategoryHitAt3() { return categoryHitAt3; }
        public String getTopCategory() { return topCategory; }
        public List<String> getTopTitles() { return topTitles; }
    }
}
