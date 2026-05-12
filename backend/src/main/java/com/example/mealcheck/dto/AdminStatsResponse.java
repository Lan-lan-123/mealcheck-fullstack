package com.example.mealcheck.dto;

public class AdminStatsResponse {

    private long userCount;
    private long mealRecordCount;
    private long todayMealRecordCount;
    private long knowledgeChunkCount;

    public AdminStatsResponse() {
    }

    public AdminStatsResponse(long userCount,
                              long mealRecordCount,
                              long todayMealRecordCount,
                              long knowledgeChunkCount) {
        this.userCount = userCount;
        this.mealRecordCount = mealRecordCount;
        this.todayMealRecordCount = todayMealRecordCount;
        this.knowledgeChunkCount = knowledgeChunkCount;
    }

    public long getUserCount() {
        return userCount;
    }

    public void setUserCount(long userCount) {
        this.userCount = userCount;
    }

    public long getMealRecordCount() {
        return mealRecordCount;
    }

    public void setMealRecordCount(long mealRecordCount) {
        this.mealRecordCount = mealRecordCount;
    }

    public long getTodayMealRecordCount() {
        return todayMealRecordCount;
    }

    public void setTodayMealRecordCount(long todayMealRecordCount) {
        this.todayMealRecordCount = todayMealRecordCount;
    }

    public long getKnowledgeChunkCount() {
        return knowledgeChunkCount;
    }

    public void setKnowledgeChunkCount(long knowledgeChunkCount) {
        this.knowledgeChunkCount = knowledgeChunkCount;
    }
}