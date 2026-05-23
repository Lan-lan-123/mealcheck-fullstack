package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public class UserGoalRequest {
    @NotBlank
    private String goalType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String note;

    public String getGoalType() { return goalType; }
    public void setGoalType(String goalType) { this.goalType = goalType; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
