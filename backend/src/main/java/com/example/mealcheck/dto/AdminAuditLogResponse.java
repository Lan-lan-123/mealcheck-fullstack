package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class AdminAuditLogResponse {
    private Long id;
    private String adminUsername;
    private String action;
    private String targetType;
    private Long targetId;
    private String detail;
    private LocalDateTime createdAt;

    public AdminAuditLogResponse(Long id,
                                 String adminUsername,
                                 String action,
                                 String targetType,
                                 Long targetId,
                                 String detail,
                                 LocalDateTime createdAt) {
        this.id = id;
        this.adminUsername = adminUsername;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getAdminUsername() { return adminUsername; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
