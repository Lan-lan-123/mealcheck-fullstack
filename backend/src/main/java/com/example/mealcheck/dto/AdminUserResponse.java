package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class AdminUserResponse {

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime lastUploadAt;

    public AdminUserResponse() {
    }

    public AdminUserResponse(Long id,
                             String username,
                             String displayName,
                             String role,
                             LocalDateTime createdAt,
                             LocalDateTime lastUploadAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = createdAt;
        this.lastUploadAt = lastUploadAt;
    }

    public Long getId() {
        return id;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastUploadAt() {
        return lastUploadAt;
    }
}
