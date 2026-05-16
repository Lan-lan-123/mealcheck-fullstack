package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public class HealthResponse {
    private String status;
    private boolean databaseReachable;
    private boolean redisReachable;
    private boolean aiConfigured;
    private String aiModel;
    private long knowledgeChunkCount;
    private boolean uploadDirectoryWritable;
    private List<AiCallStatusResponse> aiCalls;
    private LocalDateTime checkedAt;

    public HealthResponse(String status,
                          boolean databaseReachable,
                          boolean redisReachable,
                          boolean aiConfigured,
                          String aiModel,
                          long knowledgeChunkCount,
                          boolean uploadDirectoryWritable,
                          List<AiCallStatusResponse> aiCalls,
                          LocalDateTime checkedAt) {
        this.status = status;
        this.databaseReachable = databaseReachable;
        this.redisReachable = redisReachable;
        this.aiConfigured = aiConfigured;
        this.aiModel = aiModel;
        this.knowledgeChunkCount = knowledgeChunkCount;
        this.uploadDirectoryWritable = uploadDirectoryWritable;
        this.aiCalls = aiCalls;
        this.checkedAt = checkedAt;
    }

    public String getStatus() {
        return status;
    }

    public boolean isDatabaseReachable() {
        return databaseReachable;
    }

    public boolean isRedisReachable() {
        return redisReachable;
    }

    public boolean isAiConfigured() {
        return aiConfigured;
    }

    public String getAiModel() {
        return aiModel;
    }

    public long getKnowledgeChunkCount() {
        return knowledgeChunkCount;
    }

    public boolean isUploadDirectoryWritable() {
        return uploadDirectoryWritable;
    }

    public List<AiCallStatusResponse> getAiCalls() {
        return aiCalls;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }
}
