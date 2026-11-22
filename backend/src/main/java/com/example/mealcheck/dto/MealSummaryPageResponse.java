package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MealSummaryPageResponse(List<MealSummary> content, long totalElements,
                                      long totalPages, int page, int size) {
    public record MealSummary(long id, LocalDateTime createdAt, LocalDateTime occurredAt,
                              String mealType, String recordSource, boolean timeConfirmed,
                              String goal, int score, String summary, List<String> foodNames) { }
}
