package com.example.mealcheck.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record MealCorrectionRequest(@Min(0) long expectedRevision,
        @NotEmpty @Size(max=30) List<@NotNull @Valid CorrectedFood> foods,
        @NotBlank @Size(max=500) String reason) {
    public record CorrectedFood(@NotBlank @Size(max=100) String name,
            @NotBlank @Pattern(regexp="staple|protein|vegetable|fruit|dairy|soup|drink|dessert|fried|oily|other") String category) { }
}
