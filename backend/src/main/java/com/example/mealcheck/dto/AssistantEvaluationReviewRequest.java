package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AssistantEvaluationReviewRequest(
        @NotBlank
        @Pattern(regexp = "PASSED|FAILED", message = "人工复核结论仅支持 PASSED 或 FAILED")
        String verdict,
        @Size(max = 2000, message = "人工复核备注不能超过 2000 字")
        String notes
) {
}
