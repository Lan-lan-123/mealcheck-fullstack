package com.example.mealcheck.controller;

import com.example.mealcheck.service.AiBudgetService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AiBudgetMonitoringController {
    private final AiBudgetService budgets;
    public AiBudgetMonitoringController(AiBudgetService budgets) { this.budgets=budgets; }
    @GetMapping("/api/admin/ai-budget") public Map<String,Object> summary() { return budgets.monitoring(); }
}
