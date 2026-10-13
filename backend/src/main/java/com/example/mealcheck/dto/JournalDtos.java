package com.example.mealcheck.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import com.example.mealcheck.dto.MealCorrectionRequest.CorrectedFood;

public final class JournalDtos {
    private JournalDtos() { }
    public record ManualMeal(@NotBlank @Size(max=64) String occurredAt,
        @Pattern(regexp="BREAKFAST|LUNCH|DINNER|SNACK|UNSPECIFIED") @NotNull String mealType,
        @NotBlank String goal, @Size(max=500) String note,
        @NotEmpty @Size(max=30) List<@NotNull @Valid CorrectedFood> foods) { }
    public record Metadata(@NotBlank @Size(max=64) String occurredAt,
        @Pattern(regexp="BREAKFAST|LUNCH|DINNER|SNACK|UNSPECIFIED") @NotNull String mealType,
        @Size(max=500) String note, @Min(0) long expectedVersion) { }
    public record SkipSlot(@NotNull LocalDate day,
        @Pattern(regexp="BREAKFAST|LUNCH|DINNER|SNACK") @NotNull String mealType, @Size(max=500) String note) { }
    public record BehaviorGoal(@Pattern(regexp="RECORD_DAYS_WEEKLY|MEALS_DAILY") @NotNull String kind,
        @Min(1) @Max(7) int target, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
        boolean active, @Min(0) long expectedVersion) { }
    public record Plan(@NotBlank @Size(max=120) String title, @NotBlank @Size(max=2000) String actionText,
        @Pattern(regexp="MANUAL|MEAL|ASSISTANT|REPORT") @NotNull String sourceType,
        Long sourceRecordId, Long sourceMessageId, @NotNull LocalDate plannedDate, LocalDate sourcePeriodStart,LocalDate sourcePeriodEnd) {
        public Plan(String title,String actionText,String sourceType,Long sourceRecordId,Long sourceMessageId,LocalDate plannedDate) {
            this(title,actionText,sourceType,sourceRecordId,sourceMessageId,plannedDate,null,null);
        }
    }
    public record PlanUpdate(@NotBlank @Size(max=120) String title, @NotBlank @Size(max=2000) String actionText,
        @NotNull LocalDate plannedDate, @Pattern(regexp="OPEN|DONE|SKIPPED") @NotNull String status,
        @Size(max=500) String note, @Min(0) long expectedVersion) { }
    public record Feedback(@Pattern(regexp="HELPFUL|UNHELPFUL") @NotNull String verdict,
        @Pattern(regexp="GENERAL|INCORRECT|IRRELEVANT|UNCLEAR|SERVICE_ERROR") @NotNull String category,
        @Size(max=500) String note) { }
}
