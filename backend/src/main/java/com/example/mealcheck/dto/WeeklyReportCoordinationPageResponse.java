package com.example.mealcheck.dto;

import java.util.List;

public record WeeklyReportCoordinationPageResponse(
        List<WeeklyReportCoordinationResponse.RunSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
