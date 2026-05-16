package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class ApiErrorResponse {
    private final int status;
    private final String error;
    private final String code;
    private final String message;
    private final LocalDateTime timestamp;

    public ApiErrorResponse(int status, String error, String code, String message, LocalDateTime timestamp) {
        this.status = status;
        this.error = error;
        this.code = code;
        this.message = message;
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
