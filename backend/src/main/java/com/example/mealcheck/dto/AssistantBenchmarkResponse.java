package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AssistantBenchmarkResponse(
        String runId,
        String status,
        String mode,
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
        String model,
        String judgeModel,
        boolean independentJudge,
        String datasetVersion,
        String datasetFingerprint,
        String message,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<IntentResult> intents,
        List<CategoryResult> categories,
        List<CaseResult> cases
) {
    public AssistantBenchmarkResponse {
        intents = intents == null ? List.of() : List.copyOf(intents);
        categories = categories == null ? List.of() : List.copyOf(categories);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record CategoryResult(
            String category,
            int caseCount,
            int judgedCount,
            int passedCount,
            int failedCount,
            int errorCount,
            double passRate,
            double averageScore
    ) {
    }

    public record IntentResult(
            String skill,
            int expectedCount,
            int predictedCount,
            int truePositiveCount,
            double precision,
            double recall,
            double f1
    ) {
    }

    public record CaseResult(
            String id,
            String category,
            String riskLevel,
            String question,
            String status,
            boolean passed,
            double weightedScore,
            Map<String, Integer> dimensionScores,
            List<String> hardGateFailures,
            String reason,
            String answer,
            String executionPath,
            String selectedSkill,
            String expectedSkill,
            List<String> acceptableSkills,
            boolean intentMatched,
            boolean toolTracePassed,
            List<String> expectedTools,
            List<String> calledTools,
            List<String> missingExpectedTools,
            List<String> forbiddenCalledTools,
            int toolRounds,
            boolean degraded,
            List<ToolCallResult> toolCalls,
            long durationMs
    ) {
        public CaseResult {
            dimensionScores = dimensionScores == null ? Map.of() : Map.copyOf(dimensionScores);
            hardGateFailures = hardGateFailures == null ? List.of() : List.copyOf(hardGateFailures);
            acceptableSkills = acceptableSkills == null ? List.of() : List.copyOf(acceptableSkills);
            expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
            calledTools = calledTools == null ? List.of() : List.copyOf(calledTools);
            missingExpectedTools = missingExpectedTools == null ? List.of() : List.copyOf(missingExpectedTools);
            forbiddenCalledTools = forbiddenCalledTools == null ? List.of() : List.copyOf(forbiddenCalledTools);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    public record ToolCallResult(
            String name,
            String arguments,
            String status,
            int round,
            long durationMs
    ) {
    }
}
