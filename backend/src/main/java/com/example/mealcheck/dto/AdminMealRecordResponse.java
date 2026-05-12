package com.example.mealcheck.dto;

import java.time.LocalDateTime;
import java.util.List;

public class AdminMealRecordResponse {

    private Long id;
    private String username;
    private Integer score;
    private String summary;
    private List<String> foodNames;
    private LocalDateTime createdAt;

    public AdminMealRecordResponse() {
    }

    public AdminMealRecordResponse(Long id,
                                   String username,
                                   Integer score,
                                   String summary,
                                   List<String> foodNames,
                                   LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.score = score;
        this.summary = summary;
        this.foodNames = foodNames;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Integer getScore() {
        return score;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getFoodNames() {
        return foodNames;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}