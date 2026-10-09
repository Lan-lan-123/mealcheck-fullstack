package com.example.mealcheck.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AssistantEvaluationCandidateRequest(
        @NotBlank @Size(max = 1000) String question,
        @NotBlank @Size(max = 5000) String observedAnswer,
        @NotBlank @Size(max = 40) String issueType,
        @Size(max = 2000) String sanitizedContext,
        @Size(max = 80) String expectedSkill,
        List<@Size(max = 80) String> expectedTools,
        @Size(max = 2000) String notes,
        @Size(max = 200) String sourceReference,
        @AssertTrue(message = "必须确认样本已经脱敏") boolean sanitizedConfirmed
) {
}
