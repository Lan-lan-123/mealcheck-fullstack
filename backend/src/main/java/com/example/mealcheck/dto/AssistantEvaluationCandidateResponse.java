package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AssistantEvaluationCandidateResponse(List<Candidate> candidates) {
    public AssistantEvaluationCandidateResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record Candidate(
            Long id,
            String question,
            String observedAnswer,
            String issueType,
            String sanitizedContext,
            String expectedSkill,
            List<String> expectedTools,
            String notes,
            String sourceReference,
            String status,
            String submittedBy,
            String reviewedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public Candidate {
            expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        }
    }
}
