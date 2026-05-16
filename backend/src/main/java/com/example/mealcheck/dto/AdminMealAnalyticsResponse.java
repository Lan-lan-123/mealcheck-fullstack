package com.example.mealcheck.dto;

import java.util.List;

public class AdminMealAnalyticsResponse {
    private long total;
    private int averageScore;
    private List<MetricItem> scoreBands;
    private List<MetricItem> userCounts;
    private List<MetricItem> foodCounts;
    private List<MetricItem> riskTagCounts;
    private List<MetricItem> dailyAverageScores;

    public AdminMealAnalyticsResponse() {
    }

    public AdminMealAnalyticsResponse(long total,
                                      int averageScore,
                                      List<MetricItem> scoreBands,
                                      List<MetricItem> userCounts,
                                      List<MetricItem> foodCounts,
                                      List<MetricItem> riskTagCounts,
                                      List<MetricItem> dailyAverageScores) {
        this.total = total;
        this.averageScore = averageScore;
        this.scoreBands = scoreBands;
        this.userCounts = userCounts;
        this.foodCounts = foodCounts;
        this.riskTagCounts = riskTagCounts;
        this.dailyAverageScores = dailyAverageScores;
    }

    public long getTotal() {
        return total;
    }

    public int getAverageScore() {
        return averageScore;
    }

    public List<MetricItem> getScoreBands() {
        return scoreBands;
    }

    public List<MetricItem> getUserCounts() {
        return userCounts;
    }

    public List<MetricItem> getFoodCounts() {
        return foodCounts;
    }

    public List<MetricItem> getRiskTagCounts() {
        return riskTagCounts;
    }

    public List<MetricItem> getDailyAverageScores() {
        return dailyAverageScores;
    }

    public static class MetricItem {
        private String label;
        private int value;

        public MetricItem() {
        }

        public MetricItem(String label, int value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public int getValue() {
            return value;
        }
    }
}
