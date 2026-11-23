package com.example.mealcheck.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record DietPreferences(
        @NotNull Style style,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=40) @Pattern(regexp="[\\p{L}\\p{N} （）()·、-]+") String> avoidFoods,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=40) @Pattern(regexp="[\\p{L}\\p{N} （）()·、-]+") String> dislikedFoods,
        @Min(0) long version) {
    public enum Style { OMNIVORE, VEGETARIAN, VEGAN }
    public static DietPreferences defaults() { return new DietPreferences(Style.OMNIVORE, List.of(), List.of(), 0); }
    public boolean active() { return style != Style.OMNIVORE || !avoidFoods.isEmpty() || !dislikedFoods.isEmpty(); }
}
