package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public record WeeklyReportJobResponse(Long id, String status, int days, int attempts,
        LocalDateTime nextRetryAt, WeeklyReportResponse report) {}
