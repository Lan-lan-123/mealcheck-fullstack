package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AssistantBenchmarkHistoryResponse(List<RunSummary> runs) {
    public record RunSummary(
            String runId,
            String mode,
            String status,
            String verdict,
            int caseCount,
            int completedCount,
            int judgedCount,
            int passedCount,
            int failedCount,
            int errorCount,
            int hardGateFailureCount,
            int toolTracePassedCount,
            int intentEvaluatedCount,
            int intentMatchedCount,
            double passRate,
            double executionSuccessRate,
            double endToEndPassRate,
            double toolTracePassRate,
            double intentAccuracy,
            double intentMacroF1,
            double macroCategoryPassRate,
            double averageScore,
            double endToEndAverageScore,
            long elapsedMs,
            String generationModel,
            String judgeModel,
            boolean independentJudge,
            String datasetVersion,
            String datasetFingerprint,
            String startedBy,
            String message,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
    }
}
