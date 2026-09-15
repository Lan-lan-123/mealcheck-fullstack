package com.example.mealcheck.service;

import java.time.Duration;

public class RateLimitExceededException extends RuntimeException {
    private final long limit;
    private final long remaining;
    private final Duration retryAfter;

    public RateLimitExceededException(String message, long limit, long remaining, Duration retryAfter) {
        super(message);
        this.limit = limit;
        this.remaining = remaining;
        this.retryAfter = retryAfter;
    }

    public long getLimit() {
        return limit;
    }

    public long getRemaining() {
        return remaining;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
