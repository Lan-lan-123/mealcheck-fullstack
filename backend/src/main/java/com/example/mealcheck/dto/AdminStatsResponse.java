package com.example.mealcheck.dto;

public class AdminStatsResponse {

    private long userCount;
    private long mealRecordCount;
    private long todayNormalUploadCount;
    private long todayAbnormalUploadCount;
    private long knowledgeChunkCount;

    public AdminStatsResponse() {
    }

    public AdminStatsResponse(long userCount,
                              long mealRecordCount,
                              long todayNormalUploadCount,
                              long todayAbnormalUploadCount,
                              long knowledgeChunkCount) {
        this.userCount = userCount;
        this.mealRecordCount = mealRecordCount;
        this.todayNormalUploadCount = todayNormalUploadCount;
        this.todayAbnormalUploadCount = todayAbnormalUploadCount;
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

    public long getTodayNormalUploadCount() {
        return todayNormalUploadCount;
    }

    public void setTodayNormalUploadCount(long todayNormalUploadCount) {
        this.todayNormalUploadCount = todayNormalUploadCount;
    }

    public long getTodayAbnormalUploadCount() {
        return todayAbnormalUploadCount;
    }

    public void setTodayAbnormalUploadCount(long todayAbnormalUploadCount) {
        this.todayAbnormalUploadCount = todayAbnormalUploadCount;
    }

    public long getKnowledgeChunkCount() {
        return knowledgeChunkCount;
    }

    public void setKnowledgeChunkCount(long knowledgeChunkCount) {
        this.knowledgeChunkCount = knowledgeChunkCount;
    }
}
