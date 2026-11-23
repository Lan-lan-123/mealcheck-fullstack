package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealRecordResponse;
import com.example.mealcheck.dto.MealSummaryPageResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealAnalysisService;
import com.example.mealcheck.service.UserMealQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/meals")
public class MealQueryController {
    private final UserMealQueryService queries;
    private final MealAnalysisService meals;

    public MealQueryController(UserMealQueryService queries, MealAnalysisService meals) {
        this.queries = queries;
        this.meals = meals;
    }

    @GetMapping("/summaries")
    public MealSummaryPageResponse summaries(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String goal, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Integer maxScore) {
        return queries.page(user.getId(), page, size, goal, from, to, minScore, maxScore);
    }

    @GetMapping("/{id}")
    public MealRecordResponse detail(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
        return meals.detail(user, id);
    }
}
