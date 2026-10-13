package com.example.mealcheck.controller;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealCorrectionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meals")
public class MealCorrectionController {
    private final MealCorrectionService service;
    public MealCorrectionController(MealCorrectionService service) { this.service=service; }
    @PostMapping("/{id}/corrections")
    public MealAnalysisResponse correct(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id,
            @Valid @RequestBody MealCorrectionRequest request) { return service.correct(user, id, request); }
}
