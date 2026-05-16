package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class AiCallStatusResponse {
    private String operation;
    private boolean success;
    private String message;
    private LocalDateTime at;

    public AiCallStatusResponse() {
    }

    public AiCallStatusResponse(String operation, boolean success, String message, LocalDateTime at) {
        this.operation = operation;
        this.success = success;
        this.message = message;
        this.at = at;
    }

    public String getOperation() {
        return operation;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getAt() {
        return at;
    }
}
