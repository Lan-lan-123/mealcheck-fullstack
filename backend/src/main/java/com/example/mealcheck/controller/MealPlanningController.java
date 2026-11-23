package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealPlanningDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.NextMealService;
import com.example.mealcheck.service.TodayMealService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meals")
public class MealPlanningController {
    private final TodayMealService today;
    private final NextMealService next;
    public MealPlanningController(TodayMealService today, NextMealService next) { this.today = today; this.next = next; }
    @GetMapping("/today") public Today today(@AuthenticationPrincipal UserPrincipal user) { return today.get(user.getId()); }
    @PostMapping("/next-meal") public Recommendations next(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody NextMealRequest body) {
        return next.recommend(user, body);
    }
}
