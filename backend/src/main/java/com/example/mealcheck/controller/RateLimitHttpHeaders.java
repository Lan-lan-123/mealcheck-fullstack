package com.example.mealcheck.controller;

import com.example.mealcheck.service.RateLimitService;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

final class RateLimitHttpHeaders {
    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private RateLimitHttpHeaders() {
    }

    static HttpHeaders from(RateLimitService.RateLimitDecision decision) {
        return from(decision.limit(), decision.remaining(), decision.retryAfter());
    }

    static HttpHeaders from(long limit, long remaining, Duration retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(LIMIT_HEADER, String.valueOf(Math.max(0L, limit)));
        headers.set(REMAINING_HEADER, String.valueOf(Math.max(0L, remaining)));
        if (retryAfter != null && !retryAfter.isZero() && !retryAfter.isNegative()) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(toRetryAfterSeconds(retryAfter)));
        }
        return headers;
    }

    private static long toRetryAfterSeconds(Duration retryAfter) {
        long millis = retryAfter.toMillis();
        return Math.max(1L, (millis + 999L) / 1000L);
    }
}
