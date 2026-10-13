package com.example.mealcheck.controller;

import com.example.mealcheck.service.MealCorrectionEvaluationService;
import com.example.mealcheck.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/meal-corrections")
@PreAuthorize("hasRole('ADMIN')")
public class MealCorrectionEvaluationController {
    private final MealCorrectionEvaluationService service;
    public MealCorrectionEvaluationController(MealCorrectionEvaluationService service) { this.service=service; }
    @GetMapping
    public List<MealCorrectionEvaluationService.Candidate> list(@RequestParam(defaultValue="0") int page) { return service.candidates(page); }
    @PutMapping("/{id}/review")
    public void review(@PathVariable Long id, @Valid @RequestBody MealCorrectionEvaluationService.Review review,
            @AuthenticationPrincipal UserPrincipal user) { service.review(id, review, user.getUsername()); }
    @GetMapping("/evaluation")
    public MealCorrectionEvaluationService.Evaluation evaluate() { return service.evaluate(); }
    @PostMapping("/evaluation/runs")
    public MealCorrectionEvaluationService.Run run() { return service.run(); }
    @GetMapping("/evaluation/runs")
    public List<MealCorrectionEvaluationService.Run> history(@RequestParam(defaultValue="0") int page) { return service.history(page); }
    @GetMapping("/evaluation/runs/{id}")
    public MealCorrectionEvaluationService.Evaluation detail(@PathVariable long id) { return service.detail(id); }
    @GetMapping("/evaluation/compare")
    public MealCorrectionEvaluationService.Comparison compare(@RequestParam long baseId,@RequestParam long candidateId) { return service.compare(baseId,candidateId); }
}
