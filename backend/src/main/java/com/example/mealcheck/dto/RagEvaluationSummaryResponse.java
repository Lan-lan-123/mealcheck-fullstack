package com.example.mealcheck.dto;

import java.util.List;

public class RagEvaluationSummaryResponse {
    private long searches24h;
    private double averageTopScore24h;
    private double averageScore24h;
    private double averageResultCount24h;
    private long lowConfidenceSearches24h;
    private double averageRoughTopScore24h;
    private double averageRerankerTopScore24h;
    private long rerankerAppliedSearches24h;
    private long noAnswerSearches24h;
    private List<AdminMealAnalyticsResponse.MetricItem> categoryCounts;

    public RagEvaluationSummaryResponse() {
    }

    public RagEvaluationSummaryResponse(long searches24h,
                                        double averageTopScore24h,
                                        double averageScore24h,
                                        double averageResultCount24h,
                                        long lowConfidenceSearches24h,
                                        double averageRoughTopScore24h,
                                        double averageRerankerTopScore24h,
                                        long rerankerAppliedSearches24h,
                                        long noAnswerSearches24h,
                                        List<AdminMealAnalyticsResponse.MetricItem> categoryCounts) {
        this.searches24h = searches24h;
        this.averageTopScore24h = averageTopScore24h;
        this.averageScore24h = averageScore24h;
        this.averageResultCount24h = averageResultCount24h;
        this.lowConfidenceSearches24h = lowConfidenceSearches24h;
        this.averageRoughTopScore24h = averageRoughTopScore24h;
        this.averageRerankerTopScore24h = averageRerankerTopScore24h;
        this.rerankerAppliedSearches24h = rerankerAppliedSearches24h;
        this.noAnswerSearches24h = noAnswerSearches24h;
        this.categoryCounts = categoryCounts;
    }

    public long getSearches24h() { return searches24h; }
    public double getAverageTopScore24h() { return averageTopScore24h; }
    public double getAverageScore24h() { return averageScore24h; }
    public double getAverageResultCount24h() { return averageResultCount24h; }
    public long getLowConfidenceSearches24h() { return lowConfidenceSearches24h; }
    public double getAverageRoughTopScore24h() { return averageRoughTopScore24h; }
    public double getAverageRerankerTopScore24h() { return averageRerankerTopScore24h; }
    public long getRerankerAppliedSearches24h() { return rerankerAppliedSearches24h; }
    public long getNoAnswerSearches24h() { return noAnswerSearches24h; }
    public List<AdminMealAnalyticsResponse.MetricItem> getCategoryCounts() { return categoryCounts; }
}
