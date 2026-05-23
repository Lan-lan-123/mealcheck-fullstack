package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public static class RegisterRequest {
        @NotBlank
        @Size(min = 3, max = 64)
        private String username;

        @NotBlank
        @Size(min = 6, max = 128)
        private String password;

        private String displayName;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    public static class LoginRequest {
        @NotBlank
        private String username;

        @NotBlank
        private String password;

        @NotBlank
        private String captchaId;

        @NotBlank
        private String captchaAnswer;

        private String expectedRole;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getCaptchaId() {
            return captchaId;
        }

        public void setCaptchaId(String captchaId) {
            this.captchaId = captchaId;
        }

        public String getCaptchaAnswer() {
            return captchaAnswer;
        }

        public void setCaptchaAnswer(String captchaAnswer) {
            this.captchaAnswer = captchaAnswer;
        }

        public String getExpectedRole() {
            return expectedRole;
        }

        public void setExpectedRole(String expectedRole) {
            this.expectedRole = expectedRole;
        }
    }

    public static class CaptchaResponse {
        private String captchaId;
        private String question;

        public CaptchaResponse(String captchaId, String question) {
            this.captchaId = captchaId;
            this.question = question;
        }

        public String getCaptchaId() {
            return captchaId;
        }

        public String getQuestion() {
            return question;
        }
    }

    public static class AuthResponse {
        private String token;
        private String username;
        private String displayName;
        private String role;

        public AuthResponse(String token, String username, String displayName, String role) {
            this.token = token;
            this.username = username;
            this.displayName = displayName;
            this.role = role;
        }

        public String getToken() {
            return token;
        }

        public String getUsername() {
            return username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getRole() {
            return role;
        }
    }
}
