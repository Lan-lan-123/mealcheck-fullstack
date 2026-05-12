package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class MealRecordResponse {
    private Long id;
    private LocalDateTime createdAt;
    private String goal;
    private int score;
    private String summary;
    private List<FoodItem> foods;
    private Map<String, Integer> categoryCounts;
    private List<String> riskTags;
    private String advice;
    private String imageUrl;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<FoodItem> getFoods() { return foods; }
    public void setFoods(List<FoodItem> foods) { this.foods = foods; }
    public Map<String, Integer> getCategoryCounts() { return categoryCounts; }
    public void setCategoryCounts(Map<String, Integer> categoryCounts) { this.categoryCounts = categoryCounts; }
    public List<String> getRiskTags() { return riskTags; }
    public void setRiskTags(List<String> riskTags) { this.riskTags = riskTags; }
    public String getAdvice() { return advice; }
    public void setAdvice(String advice) { this.advice = advice; }
    public String getImageUrl() {
        return imageUrl;
    }
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
