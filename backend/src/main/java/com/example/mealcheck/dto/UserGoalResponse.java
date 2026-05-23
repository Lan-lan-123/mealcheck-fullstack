package com.example.mealcheck.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class UserGoalResponse {
    private Long id;
    private String goalType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String note;
    private boolean active;
    private LocalDateTime createdAt;

    public UserGoalResponse() {
    }

    public UserGoalResponse(Long id,
                            String goalType,
                            LocalDate startDate,
                            LocalDate endDate,
                            String note,
                            boolean active,
                            LocalDateTime createdAt) {
        this.id = id;
        this.goalType = goalType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.note = note;
        this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getGoalType() { return goalType; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getNote() { return note; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
