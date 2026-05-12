package com.example.mealcheck.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 64)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String role = "USER";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // 最后一次上传图片时间
    @Column(name = "last_upload_at")
    private LocalDateTime lastUploadAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role == null || role.isBlank() ? "USER" : role;
    }

    public void setRole(String role) {
        this.role = role == null || role.isBlank() ? "USER" : role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastUploadAt() {
        return lastUploadAt;
    }

    public void setLastUploadAt(LocalDateTime lastUploadAt) {
        this.lastUploadAt = lastUploadAt;
    }
}