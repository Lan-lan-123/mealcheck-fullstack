package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class RateLimitService {
    private final RedisCacheService redisCacheService;
    private final AppProperties properties;
    private final ApplicationObservability observability;

    public RateLimitService(RedisCacheService redisCacheService,
                            AppProperties properties,
                            ApplicationObservability observability) {
        this.redisCacheService = redisCacheService;
        this.properties = properties;
        this.observability = observability;
    }

    public RateLimitDecision consumeAssistant(String username) {
        return consume("assistant", username, properties.getRateLimit().getAssistant());
    }

    public RateLimitDecision consumeMealAnalysis(String username) {
        return consume("meal-analysis", username, properties.getRateLimit().getMealAnalysis());
    }

    public RateLimitDecision consumeUploadIngress(String username) {
        return consume("upload-ingress", username, properties.getRateLimit().getUploadIngress());
    }

    public RateLimitDecision consumeWeeklyReport(String userId) {
        return consume("weekly-report", userId, properties.getRateLimit().getWeeklyReport());
    }

    public RateLimitDecision consumeLogin(String clientAddress, String username) {
        return consume("login", clientAddress + ":" + normalize(username), properties.getRateLimit().getLogin());
    }

    public RateLimitDecision consumeRegistration(String clientAddress) {
        return consume("registration", clientAddress, properties.getRateLimit().getRegistration());
    }

    public RateLimitDecision consumeCaptcha(String clientAddress) {
        return consume("captcha", clientAddress, properties.getRateLimit().getCaptcha());
    }

    private RateLimitDecision consume(String scope, String username, AppProperties.TokenBucket bucket) {
        TokenBucketPolicy policy = policy(bucket);
        RateLimitDecision decision = redisCacheService.consumeTokenBucket(
                        "rate:bucket:" + scope + ":" + hashIdentifier(username),
                        policy.capacity(),
                        policy.refillTokens(),
                        policy.refillPeriod(),
                        policy.requestCost()
                )
                .map(result -> new RateLimitDecision(
                        result.allowed() ? RateLimitStatus.ALLOWED : RateLimitStatus.LIMIT_EXCEEDED,
                        policy.capacity(),
                        result.remainingTokens(),
                        result.retryAfter()
                ))
                .orElseGet(() -> new RateLimitDecision(
                        RateLimitStatus.REDIS_UNAVAILABLE,
                        policy.capacity(),
                        0L,
                        Duration.ZERO
                ));
        observability.recordRateLimit(scope, outcome(decision.status()));
        return decision;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String hashIdentifier(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String outcome(RateLimitStatus status) {
        return switch (status) {
            case ALLOWED -> "allowed";
            case LIMIT_EXCEEDED -> "rejected";
            case REDIS_UNAVAILABLE -> "redis_unavailable";
        };
    }

    private TokenBucketPolicy policy(AppProperties.TokenBucket bucket) {
        long capacity = bucket.getCapacity();
        long refillTokens = bucket.getRefillTokens();
        long refillPeriodSeconds = bucket.getRefillPeriodSeconds();
        long requestCost = bucket.getRequestCost();
        if (capacity <= 0L || refillTokens <= 0L || refillPeriodSeconds <= 0L
                || requestCost <= 0L || requestCost > capacity) {
            throw new IllegalStateException("限流配置无效，请检查令牌桶容量、补充速度和请求成本。");
        }
        return new TokenBucketPolicy(capacity, refillTokens, Duration.ofSeconds(refillPeriodSeconds), requestCost);
    }

    public enum RateLimitStatus {
        ALLOWED,
        LIMIT_EXCEEDED,
        REDIS_UNAVAILABLE
    }

    public record RateLimitDecision(RateLimitStatus status,
                                    long limit,
                                    long remaining,
                                    Duration retryAfter) {
        public boolean allowed() {
            return status == RateLimitStatus.ALLOWED;
        }

        public boolean unavailable() {
            return status == RateLimitStatus.REDIS_UNAVAILABLE;
        }
    }

    private record TokenBucketPolicy(long capacity,
                                     long refillTokens,
                                     Duration refillPeriod,
                                     long requestCost) {
    }
}
