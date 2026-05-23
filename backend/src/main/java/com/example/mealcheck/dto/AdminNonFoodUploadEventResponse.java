package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class AdminNonFoodUploadEventResponse {
    private Long id;
    private String username;
    private String displayName;
    private String reason;
    private long windowCount;
    private int windowMinutes;
    private int observedMinutes;
    private double perMinuteRate;
    private boolean thresholdReached;
    private boolean currentlyBlocked;
    private LocalDateTime createdAt;

    public AdminNonFoodUploadEventResponse(Long id,
                                           String username,
                                           String displayName,
                                           String reason,
                                           long windowCount,
                                           int windowMinutes,
                                           int observedMinutes,
                                           double perMinuteRate,
                                           boolean thresholdReached,
                                           boolean currentlyBlocked,
                                           LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.reason = reason;
        this.windowCount = windowCount;
        this.windowMinutes = windowMinutes;
        this.observedMinutes = observedMinutes;
        this.perMinuteRate = perMinuteRate;
        this.thresholdReached = thresholdReached;
        this.currentlyBlocked = currentlyBlocked;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getReason() { return reason; }
    public long getWindowCount() { return windowCount; }
    public int getWindowMinutes() { return windowMinutes; }
    public int getObservedMinutes() { return observedMinutes; }
    public double getPerMinuteRate() { return perMinuteRate; }
    public boolean isThresholdReached() { return thresholdReached; }
    public boolean isCurrentlyBlocked() { return currentlyBlocked; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
