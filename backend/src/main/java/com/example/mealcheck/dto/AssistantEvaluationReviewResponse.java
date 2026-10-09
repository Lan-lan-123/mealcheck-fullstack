package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AssistantEvaluationReviewResponse(
        String runId,
        int requiredReviewCount,
        int reviewedCount,
        int reviewedRequiredCount,
        int pendingRequiredCount,
        int agreementCount,
        int disagreementCount,
        int falsePassCount,
        int falseFailCount,
        double agreementRate,
        List<CaseReview> reviews
) {
    public AssistantEvaluationReviewResponse {
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
    }

    public record CaseReview(
            String caseId,
            String automaticStatus,
            boolean automaticPassed,
            String humanVerdict,
            String notes,
            String reviewer,
            boolean requiredReview,
            LocalDateTime updatedAt
    ) {
    }
}
