package com.example.mealcheck.dto;

import java.util.List;

public class UserMealTrendResponse {
    private int days;
    private long total;
    private int averageScore;
    private long lowScoreCount;
    private long highScoreCount;
    private String scoreTrend;
    private String suggestion;
    private List<MetricItem> dailyAverageScores;
    private List<MetricItem> foodCounts;
    private List<MetricItem> riskTagCounts;

    public UserMealTrendResponse() {
    }

    public UserMealTrendResponse(int days,
                                 long total,
                                 int averageScore,
                                 long lowScoreCount,
                                 long highScoreCount,
                                 String scoreTrend,
                                 String suggestion,
                                 List<MetricItem> dailyAverageScores,
                                 List<MetricItem> foodCounts,
                                 List<MetricItem> riskTagCounts) {
        this.days = days;
        this.total = total;
        this.averageScore = averageScore;
        this.lowScoreCount = lowScoreCount;
        this.highScoreCount = highScoreCount;
        this.scoreTrend = scoreTrend;
        this.suggestion = suggestion;
        this.dailyAverageScores = dailyAverageScores;
        this.foodCounts = foodCounts;
        this.riskTagCounts = riskTagCounts;
    }

    public int getDays() {
        return days;
    }

    public long getTotal() {
        return total;
    }

    public int getAverageScore() {
        return averageScore;
    }

    public long getLowScoreCount() {
        return lowScoreCount;
    }

    public long getHighScoreCount() {
        return highScoreCount;
    }

    public String getScoreTrend() {
        return scoreTrend;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public List<MetricItem> getDailyAverageScores() {
        return dailyAverageScores;
    }

    public List<MetricItem> getFoodCounts() {
        return foodCounts;
    }

    public List<MetricItem> getRiskTagCounts() {
        return riskTagCounts;
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
