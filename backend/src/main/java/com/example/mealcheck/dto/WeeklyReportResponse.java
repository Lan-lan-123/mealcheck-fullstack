package com.example.mealcheck.dto;

import java.io.Serializable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

public class WeeklyReportResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    private int days;
    private int totalMeals;
    private double averageScore;
    private Map<String, Integer> categoryTotals = new LinkedHashMap<>();
    private Map<String, Integer> riskTotals = new LinkedHashMap<>();
    private List<String> highlights;
    private String reportText;
    private List<String> nextWeekSuggestions;
    private String goalType;
    private LocalDateTime generatedAt;
    private String generationMode = "RULE_BASED";
    private String reviewStatus = "NOT_REQUIRED";
    private List<String> agentTrace = List.of();

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }
    public int getTotalMeals() { return totalMeals; }
    public void setTotalMeals(int totalMeals) { this.totalMeals = totalMeals; }
    public double getAverageScore() { return averageScore; }
    public void setAverageScore(double averageScore) { this.averageScore = averageScore; }
    public Map<String, Integer> getCategoryTotals() { return categoryTotals; }
    public void setCategoryTotals(Map<String, Integer> categoryTotals) { this.categoryTotals = categoryTotals; }
    public Map<String, Integer> getRiskTotals() { return riskTotals; }
    public void setRiskTotals(Map<String, Integer> riskTotals) { this.riskTotals = riskTotals; }
    public List<String> getHighlights() { return highlights; }
    public void setHighlights(List<String> highlights) { this.highlights = highlights; }
    public String getReportText() { return reportText; }
    public void setReportText(String reportText) { this.reportText = reportText; }
    public List<String> getNextWeekSuggestions() { return nextWeekSuggestions; }
    public void setNextWeekSuggestions(List<String> nextWeekSuggestions) { this.nextWeekSuggestions = nextWeekSuggestions; }
    public String getGoalType() { return goalType; }
    public void setGoalType(String goalType) { this.goalType = goalType; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public String getGenerationMode() { return generationMode; }
    public void setGenerationMode(String generationMode) { this.generationMode = generationMode; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public List<String> getAgentTrace() { return agentTrace; }
    public void setAgentTrace(List<String> agentTrace) { this.agentTrace = agentTrace; }
}
