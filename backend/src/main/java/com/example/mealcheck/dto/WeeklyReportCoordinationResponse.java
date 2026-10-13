package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WeeklyReportCoordinationResponse(
        RunSummary run,
        List<StepSummary> steps
) {
    public record RunSummary(
            String runId,
            Long jobId,
            Long userId,
            String status,
            String generationMode,
            String reviewStatus,
            int revisionCount,
            int stepCount,
            int degradedStepCount,
            int failedStepCount,
            long elapsedMs,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime updatedAt
    ) {}

    public record StepSummary(
            Long id,
            String nodeName,
            String nodeType,
            int attempt,
            String status,
            String outcome,
            Boolean aiGenerated,
            boolean fallbackUsed,
            long latencyMs,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {}
}
