package com.example.mealcheck.dto;

import java.util.List;

public class RagEvaluationSummaryResponse {
    private long searches24h;
    private double averageTopScore24h;
    private double averageScore24h;
    private double averageResultCount24h;
    private long lowConfidenceSearches24h;
    private List<AdminMealAnalyticsResponse.MetricItem> categoryCounts;

    public RagEvaluationSummaryResponse() {
    }

    public RagEvaluationSummaryResponse(long searches24h,
                                        double averageTopScore24h,
                                        double averageScore24h,
                                        double averageResultCount24h,
                                        long lowConfidenceSearches24h,
                                        List<AdminMealAnalyticsResponse.MetricItem> categoryCounts) {
        this.searches24h = searches24h;
        this.averageTopScore24h = averageTopScore24h;
        this.averageScore24h = averageScore24h;
        this.averageResultCount24h = averageResultCount24h;
        this.lowConfidenceSearches24h = lowConfidenceSearches24h;
        this.categoryCounts = categoryCounts;
    }

    public long getSearches24h() { return searches24h; }
    public double getAverageTopScore24h() { return averageTopScore24h; }
    public double getAverageScore24h() { return averageScore24h; }
    public double getAverageResultCount24h() { return averageResultCount24h; }
    public long getLowConfidenceSearches24h() { return lowConfidenceSearches24h; }
    public List<AdminMealAnalyticsResponse.MetricItem> getCategoryCounts() { return categoryCounts; }
}
