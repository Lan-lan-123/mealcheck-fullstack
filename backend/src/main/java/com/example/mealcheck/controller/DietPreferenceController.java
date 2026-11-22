package com.example.mealcheck.controller;

import com.example.mealcheck.dto.DietPreferences;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.DietPreferenceService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile/diet-preferences")
public class DietPreferenceController {
    private final DietPreferenceService service;
    public DietPreferenceController(DietPreferenceService service) { this.service=service; }
    @GetMapping public DietPreferences get(@AuthenticationPrincipal UserPrincipal user) { return service.get(user.getId()); }
    @PutMapping public DietPreferences save(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody DietPreferences request) { return service.save(user.getId(),request); }
}
