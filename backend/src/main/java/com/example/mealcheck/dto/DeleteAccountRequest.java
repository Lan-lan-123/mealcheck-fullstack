package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;

public class DeleteAccountRequest {
    @NotBlank
    @com.example.mealcheck.validation.PasswordByteLength
    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
