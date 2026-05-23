package com.example.mealcheck.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

@Service
public class TokenBlacklistService {
    private static final String PREFIX = "auth:blacklist:";

    private final RedisCacheService redisCacheService;

    public TokenBlacklistService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    public void blacklist(String token, Duration ttl) {
        if (token == null || token.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        boolean stored = redisCacheService.set(PREFIX + hash(token), "1", ttl);
        if (!stored) {
            throw new IllegalStateException("Logout failed because Redis token blacklist is unavailable.");
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return redisCacheService.getRequired(PREFIX + hash(token)).isPresent();
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }
}
