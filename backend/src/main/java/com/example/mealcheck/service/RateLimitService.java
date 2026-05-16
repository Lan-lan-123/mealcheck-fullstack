package com.example.mealcheck.service;

import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {
    private final RedisCacheService redisCacheService;

    public RateLimitService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    public boolean allow(String key, int maxRequests, Duration window) {
        long current = redisCacheService.increment("rate:" + key, window);
        return current == 0L || current <= maxRequests;
    }
}
