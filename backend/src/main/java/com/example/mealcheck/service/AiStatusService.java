package com.example.mealcheck.service;

import com.example.mealcheck.dto.AiCallStatusResponse;
import com.example.mealcheck.dto.AiOperationMetricsResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AiStatusService {
    private final Map<String, AiCallStatusResponse> statuses = new ConcurrentHashMap<>();
    private final Map<String, MutableMetrics> metrics = new ConcurrentHashMap<>();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public void recordSuccess(String operation) {
        recordSuccess(operation, 0L);
    }

    public void recordSuccess(String operation, long latencyMs) {
        successCount.incrementAndGet();
        metrics.computeIfAbsent(operation, ignored -> new MutableMetrics()).record(true, latencyMs);
        statuses.put(operation, new AiCallStatusResponse(operation, true, "最近调用成功", LocalDateTime.now()));
    }

    public void recordFailure(String operation, String message) {
        recordFailure(operation, message, 0L);
    }

    public void recordFailure(String operation, String message, long latencyMs) {
        failureCount.incrementAndGet();
        metrics.computeIfAbsent(operation, ignored -> new MutableMetrics()).record(false, latencyMs);
        String safeMessage = message == null || message.isBlank() ? "最近调用失败" : message;
        statuses.put(operation, new AiCallStatusResponse(operation, false, safeMessage, LocalDateTime.now()));
    }

    public List<AiCallStatusResponse> recentCalls() {
        return statuses.values().stream()
                .sorted((left, right) -> right.getAt().compareTo(left.getAt()))
                .toList();
    }

    public List<AiOperationMetricsResponse> metrics() {
        return metrics.entrySet().stream()
                .map(entry -> entry.getValue().toResponse(entry.getKey()))
                .sorted((left, right) -> Long.compare(right.getCalls(), left.getCalls()))
                .toList();
    }

    public long successCount() {
        return successCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    private static class MutableMetrics {
        private long calls;
        private long success;
        private long failure;
        private long latencyTotalMs;
        private long maxLatencyMs;

        synchronized void record(boolean successful, long latencyMs) {
            calls++;
            if (successful) {
                success++;
            } else {
                failure++;
            }
            latencyTotalMs += Math.max(0, latencyMs);
            maxLatencyMs = Math.max(maxLatencyMs, latencyMs);
        }

        synchronized AiOperationMetricsResponse toResponse(String operation) {
            double average = calls == 0 ? 0.0 : Math.round((double) latencyTotalMs / calls * 100.0) / 100.0;
            return new AiOperationMetricsResponse(operation, calls, success, failure, average, maxLatencyMs);
        }
    }
}
