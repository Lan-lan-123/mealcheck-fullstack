package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealAnalysisRequestResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealAnalysisRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/meals/analysis-requests")
public class MealAnalysisRequestController {
    private final MealAnalysisRequestService service;
    public MealAnalysisRequestController(MealAnalysisRequestService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<MealAnalysisRequestResponse> submit(@AuthenticationPrincipal UserPrincipal user,
            @RequestHeader("Idempotency-Key") String key, @RequestParam("image") MultipartFile image,
            @RequestParam(value="goal", defaultValue="current") String goal) {
        return response(service.enqueue(user, key, image, goal));
    }
    @GetMapping("/{key}")
    public ResponseEntity<MealAnalysisRequestResponse> status(@AuthenticationPrincipal UserPrincipal user, @PathVariable String key) {
        return response(service.status(user, key));
    }
    private ResponseEntity<MealAnalysisRequestResponse> response(MealAnalysisRequestResponse value) {
        return ("PROCESSING".equals(value.status()) || "PENDING".equals(value.status())) ? ResponseEntity.accepted().header("Retry-After", Integer.toString(value.retryAfterSeconds())).body(value) : ResponseEntity.ok(value);
    }
}
