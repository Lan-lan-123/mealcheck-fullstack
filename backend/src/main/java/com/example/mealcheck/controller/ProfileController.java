package com.example.mealcheck.controller;

import com.example.mealcheck.dto.UserGoalRequest;
import com.example.mealcheck.dto.UserGoalResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.UserGoalService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/profile/goals")
public class ProfileController {
    private final UserGoalService userGoalService;

    public ProfileController(UserGoalService userGoalService) {
        this.userGoalService = userGoalService;
    }

    @GetMapping("/current")
    public UserGoalResponse current(@AuthenticationPrincipal UserPrincipal principal) {
        return userGoalService.current(principal);
    }

    @PutMapping("/current")
    public UserGoalResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                   @Valid @RequestBody UserGoalRequest request) {
        return userGoalService.update(principal, request);
    }

    @GetMapping
    public List<UserGoalResponse> history(@AuthenticationPrincipal UserPrincipal principal) {
        return userGoalService.history(principal);
    }
}
