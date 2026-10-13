package com.example.mealcheck.controller;

import com.example.mealcheck.dto.DeleteAccountRequest;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.UserAccountDeletionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile/account")
public class AccountController {
    private final UserAccountDeletionService userAccountDeletionService;

    public AccountController(UserAccountDeletionService userAccountDeletionService) {
        this.userAccountDeletionService = userAccountDeletionService;
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                       @Valid @RequestBody DeleteAccountRequest request) {
        userAccountDeletionService.deleteCurrentAccount(principal, request.getPassword());
        return ResponseEntity.noContent().build();
    }
}
