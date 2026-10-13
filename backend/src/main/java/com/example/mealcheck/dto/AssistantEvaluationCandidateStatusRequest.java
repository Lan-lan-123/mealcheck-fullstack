package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AssistantEvaluationCandidateStatusRequest(
        @NotBlank
        @Pattern(regexp = "PENDING|APPROVED|REJECTED")
        String status
) {
}
