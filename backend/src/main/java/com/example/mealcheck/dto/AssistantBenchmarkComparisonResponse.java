package com.example.mealcheck.dto;

import java.util.List;

public record AssistantBenchmarkComparisonResponse(
        String baseRunId,
        String candidateRunId,
        String baseMode,
        String candidateMode,
        String baseDatasetVersion,
        String candidateDatasetVersion,
        String baseDatasetFingerprint,
        String candidateDatasetFingerprint,
        boolean directlyComparable,
        List<String> warnings,
        double passRateDelta,
        double endToEndPassRateDelta,
        double intentAccuracyDelta,
        double toolTracePassRateDelta,
        double averageScoreDelta,
        long elapsedMsDelta,
        int fixedCaseCount,
        int regressedCaseCount,
        List<CategoryDelta> categories,
        List<CaseChange> caseChanges
) {
    public AssistantBenchmarkComparisonResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        categories = categories == null ? List.of() : List.copyOf(categories);
        caseChanges = caseChanges == null ? List.of() : List.copyOf(caseChanges);
    }

    public record CategoryDelta(
            String category,
            double basePassRate,
            double candidatePassRate,
            double passRateDelta,
            double baseAverageScore,
            double candidateAverageScore,
            double averageScoreDelta
    ) {
    }

    public record CaseChange(
            String caseId,
            String question,
            String changeType,
            String baseStatus,
            String candidateStatus,
            boolean basePassed,
            boolean candidatePassed,
            String baseSkill,
            String candidateSkill,
            boolean baseToolTracePassed,
            boolean candidateToolTracePassed
    ) {
    }
}
