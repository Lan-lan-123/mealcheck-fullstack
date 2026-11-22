package com.example.mealcheck.controller;

import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.AiCostSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AiCostSettingsController {
    private final AiCostSettingsService settings;
    public AiCostSettingsController(AiCostSettingsService settings) { this.settings=settings; }
    @GetMapping("/api/admin/ai-cost-settings") @PreAuthorize("hasRole('ADMIN')")
    public AiCostSettingsService.View view() { return settings.view(); }
    @PutMapping("/api/admin/ai-cost-settings") @PreAuthorize("hasRole('ADMIN')")
    public AiCostSettingsService.View save(@Valid @RequestBody AiCostSettingsService.Update request,
            @AuthenticationPrincipal UserPrincipal user) { return settings.save(request,user); }
    @PostMapping("/api/admin/ai-cost-settings/reset") @PreAuthorize("hasRole('ADMIN')")
    public AiCostSettingsService.View reset(@Valid @RequestBody AiCostSettingsService.Version request,
            @AuthenticationPrincipal UserPrincipal user) { return settings.reset(request,user); }
    @GetMapping("/api/profile/ai-cost-settings")
    public AiCostSettingsService.PersonalView personal(@AuthenticationPrincipal UserPrincipal user) { return settings.personalView(user.getId()); }
    @PutMapping("/api/profile/ai-cost-settings")
    public AiCostSettingsService.PersonalView savePersonal(@Valid @RequestBody AiCostSettingsService.PersonalUpdate request,
            @AuthenticationPrincipal UserPrincipal user) { return settings.savePersonal(user.getId(),request); }
}
