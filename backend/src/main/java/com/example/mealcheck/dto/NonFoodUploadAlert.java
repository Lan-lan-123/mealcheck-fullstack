package com.example.mealcheck.dto;

import java.time.LocalDateTime;

public class NonFoodUploadAlert {
    private String username;
    private String displayName;
    private long count;
    private int limit;
    private int windowMinutes;
    private LocalDateTime lastTriggeredAt;

    public NonFoodUploadAlert() {
    }

    public NonFoodUploadAlert(String username,
                              String displayName,
                              long count,
                              int limit,
                              int windowMinutes,
                              LocalDateTime lastTriggeredAt) {
        this.username = username;
        this.displayName = displayName;
        this.count = count;
        this.limit = limit;
        this.windowMinutes = windowMinutes;
        this.lastTriggeredAt = lastTriggeredAt;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public LocalDateTime getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(LocalDateTime lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }
}
