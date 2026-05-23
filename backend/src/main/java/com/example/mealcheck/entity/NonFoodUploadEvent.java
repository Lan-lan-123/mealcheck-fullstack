package com.example.mealcheck.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "non_food_upload_events")
public class NonFoodUploadEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(length = 64)
    private String displayName;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private long windowCount;

    @Column(nullable = false)
    private int windowMinutes;

    @Column(nullable = false, columnDefinition = "integer default 1")
    private int observedMinutes = 1;

    @Column(nullable = false)
    private boolean thresholdReached;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public long getWindowCount() { return windowCount; }
    public void setWindowCount(long windowCount) { this.windowCount = windowCount; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public int getObservedMinutes() { return observedMinutes; }
    public void setObservedMinutes(int observedMinutes) { this.observedMinutes = observedMinutes; }
    public boolean isThresholdReached() { return thresholdReached; }
    public void setThresholdReached(boolean thresholdReached) { this.thresholdReached = thresholdReached; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
