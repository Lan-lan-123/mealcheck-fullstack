package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public class UserDietProfileResponse {
    private long totalMeals;
    private int averageScore;
    private String preferredGoal;
    private List<String> commonFoods;
    private List<String> commonRisks;
    private String profileSummary;
    private LocalDateTime updatedAt;

    public UserDietProfileResponse(long totalMeals,
                                   int averageScore,
                                   String preferredGoal,
                                   List<String> commonFoods,
                                   List<String> commonRisks,
                                   String profileSummary,
                                   LocalDateTime updatedAt) {
        this.totalMeals = totalMeals;
        this.averageScore = averageScore;
        this.preferredGoal = preferredGoal;
        this.commonFoods = commonFoods;
        this.commonRisks = commonRisks;
        this.profileSummary = profileSummary;
        this.updatedAt = updatedAt;
    }

    public long getTotalMeals() { return totalMeals; }
    public int getAverageScore() { return averageScore; }
    public String getPreferredGoal() { return preferredGoal; }
    public List<String> getCommonFoods() { return commonFoods; }
    public List<String> getCommonRisks() { return commonRisks; }
    public String getProfileSummary() { return profileSummary; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
