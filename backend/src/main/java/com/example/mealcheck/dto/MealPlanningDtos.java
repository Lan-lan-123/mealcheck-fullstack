package com.example.mealcheck.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class MealPlanningDtos {
    private MealPlanningDtos() { }
    public record ReviewMeal(long recordId, String mealType, List<String> foods, int foodCount) { }
    public record Today(LocalDate date, String businessZone, int totalMeals, Map<String, String> slots,
                        int reviewFoodCount, int reviewMealCount, List<ReviewMeal> reviews,
                        long pendingPlanCount, List<TrackingDtos.PlanView> pendingPlans) { }
    public record NextMealRequest(
            @NotNull @Pattern(regexp="BREAKFAST|LUNCH|DINNER") String mealType,
            @NotNull @Pattern(regexp="CANTEEN|TAKEOUT|HOME") String scene,
            @NotNull @Min(1) @Max(500) Integer budget,
            @NotNull @Min(5) @Max(120) Integer availableMinutes) { }
    public record MealOption(String id, String title, List<String> foods, int costMin, int costMax,
                             int readyMinutes, List<String> reasons, String actionText) { }
    public record Recommendations(LocalDate date, NextMealRequest constraints, String goal,
                                  String preferenceSummary, int recentMealCount, String context,
                                  String costBasis, String timeBasis, List<MealOption> options, String message) { }
}
