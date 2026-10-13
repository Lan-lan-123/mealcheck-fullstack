package com.example.mealcheck.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class MealNutritionDtos {
    private MealNutritionDtos() { }

    public record Update(@NotNull Boolean enabled, @Min(0) long expectedRevision,
                         @Min(0) long expectedVersion,
                         @NotNull @Size(max = 30) List<@NotNull @Valid Input> items) { }

    public record Input(@Min(0) @Max(29) int foodIndex,
                        @DecimalMin("0.1") @DecimalMax("10000") @Digits(integer = 5, fraction = 2) BigDecimal grams,
                        @Valid Nutrients per100g, @Size(max = 200) String source) { }

    public record Nutrients(
            @DecimalMin("0") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal energyKcal,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal proteinGrams,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal fatGrams,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal carbohydrateGrams) { }

    public record Item(int foodIndex, String name, String category, BigDecimal grams,
                       Nutrients per100g, String source, String portionSource, Nutrients estimate) { }

    public record Response(long recordId, long recordRevision, long version, boolean enabled,
                           String status, String calculationVersion, String warning,
                           List<Item> items, int estimatedFoods, Nutrients totals) { }
}
