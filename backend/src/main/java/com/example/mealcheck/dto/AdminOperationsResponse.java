package com.example.mealcheck.dto;

public class AdminOperationsResponse {
    private long activeUsers7d;
    private long mealUploads7d;
    private long aiSuccessCount;
    private long aiFailureCount;
    private int activeNonFoodAlerts;

    public AdminOperationsResponse() {
    }

    public AdminOperationsResponse(long activeUsers7d,
                                   long mealUploads7d,
                                   long aiSuccessCount,
                                   long aiFailureCount,
                                   int activeNonFoodAlerts) {
        this.activeUsers7d = activeUsers7d;
        this.mealUploads7d = mealUploads7d;
        this.aiSuccessCount = aiSuccessCount;
        this.aiFailureCount = aiFailureCount;
        this.activeNonFoodAlerts = activeNonFoodAlerts;
    }

    public long getActiveUsers7d() { return activeUsers7d; }
    public void setActiveUsers7d(long activeUsers7d) { this.activeUsers7d = activeUsers7d; }
    public long getMealUploads7d() { return mealUploads7d; }
    public void setMealUploads7d(long mealUploads7d) { this.mealUploads7d = mealUploads7d; }
    public long getAiSuccessCount() { return aiSuccessCount; }
    public void setAiSuccessCount(long aiSuccessCount) { this.aiSuccessCount = aiSuccessCount; }
    public long getAiFailureCount() { return aiFailureCount; }
    public void setAiFailureCount(long aiFailureCount) { this.aiFailureCount = aiFailureCount; }
    public int getActiveNonFoodAlerts() { return activeNonFoodAlerts; }
    public void setActiveNonFoodAlerts(int activeNonFoodAlerts) { this.activeNonFoodAlerts = activeNonFoodAlerts; }
}
