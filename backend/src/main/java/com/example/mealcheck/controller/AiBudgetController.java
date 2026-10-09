package com.example.mealcheck.controller;

import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.AiBudgetService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile/ai-budget")
public class AiBudgetController {
    private final AiBudgetService budgets;
    public AiBudgetController(AiBudgetService budgets) { this.budgets=budgets; }
    @GetMapping public java.util.Map<String,Object> usage(@AuthenticationPrincipal UserPrincipal user) { return budgets.usage(user.getId()); }
}
