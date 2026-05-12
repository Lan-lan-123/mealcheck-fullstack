package com.example.mealcheck.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeeklyReportResponse {
    private int days;
    private int totalMeals;
    private double averageScore;
    private Map<String, Integer> categoryTotals = new LinkedHashMap<>();
    private Map<String, Integer> riskTotals = new LinkedHashMap<>();
    private List<String> highlights;
    private String reportText;

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
}
