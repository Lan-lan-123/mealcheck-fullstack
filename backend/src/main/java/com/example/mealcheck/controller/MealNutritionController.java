package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealNutritionDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealNutritionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meals/{id}/nutrition")
public class MealNutritionController {
    private final MealNutritionService nutrition;
    public MealNutritionController(MealNutritionService nutrition) { this.nutrition = nutrition; }

    @GetMapping
    public Response get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable long id) {
        return nutrition.get(principal, id);
    }

    @PutMapping
    public Response update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable long id,
                           @Valid @RequestBody Update request) {
        return nutrition.update(principal, id, request);
    }
}
