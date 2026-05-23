package com.example.mealcheck.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RecognitionResult {
    private List<FoodItem> foods = new ArrayList<>();
    private String sceneSummary;
    private boolean foodImage = true;
    private String rejectionReason;
    private boolean demoMode;

    public List<FoodItem> getFoods() { return foods; }
    public void setFoods(List<FoodItem> foods) { this.foods = foods; }
    public String getSceneSummary() { return sceneSummary; }
    public void setSceneSummary(String sceneSummary) { this.sceneSummary = sceneSummary; }
    public boolean isFoodImage() { return foodImage; }
    public void setFoodImage(boolean foodImage) { this.foodImage = foodImage; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public boolean isDemoMode() { return demoMode; }
    public void setDemoMode(boolean demoMode) { this.demoMode = demoMode; }
}
