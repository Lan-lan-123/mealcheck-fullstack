package com.example.mealcheck.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RecognitionResult {
    private List<FoodItem> foods = new ArrayList<>();
    private String sceneSummary;
    private boolean demoMode;

    public List<FoodItem> getFoods() { return foods; }
    public void setFoods(List<FoodItem> foods) { this.foods = foods; }
    public String getSceneSummary() { return sceneSummary; }
    public void setSceneSummary(String sceneSummary) { this.sceneSummary = sceneSummary; }
    public boolean isDemoMode() { return demoMode; }
    public void setDemoMode(boolean demoMode) { this.demoMode = demoMode; }
}
