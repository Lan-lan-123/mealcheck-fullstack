package com.example.mealcheck.dto;

public class AiOperationMetricsResponse {
    private String operation;
    private long calls;
    private long successCount;
    private long failureCount;
    private double averageLatencyMs;
    private long maxLatencyMs;

    public AiOperationMetricsResponse() {
    }

    public AiOperationMetricsResponse(String operation,
                                      long calls,
                                      long successCount,
                                      long failureCount,
                                      double averageLatencyMs,
                                      long maxLatencyMs) {
        this.operation = operation;
        this.calls = calls;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.averageLatencyMs = averageLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    public String getOperation() { return operation; }
    public long getCalls() { return calls; }
    public long getSuccessCount() { return successCount; }
    public long getFailureCount() { return failureCount; }
    public double getAverageLatencyMs() { return averageLatencyMs; }
    public long getMaxLatencyMs() { return maxLatencyMs; }
}
