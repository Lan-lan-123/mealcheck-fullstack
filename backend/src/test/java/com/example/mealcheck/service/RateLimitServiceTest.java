package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    @Test
    void assistantUsesConfiguredTokenBucket() {
        RedisCacheService redis = mock(RedisCacheService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        AppProperties properties = new AppProperties();
        when(redis.consumeTokenBucket(
                startsWith("rate:bucket:assistant:"), eq(5L), eq(1L), eq(Duration.ofSeconds(3)), eq(1L)
        )).thenReturn(Optional.of(new RedisCacheService.TokenBucketResult(true, 4, Duration.ZERO)));
        RateLimitService service = new RateLimitService(redis, properties, observability);

        RateLimitService.RateLimitDecision decision = service.consumeAssistant("demo");

        assertThat(decision.status()).isEqualTo(RateLimitService.RateLimitStatus.ALLOWED);
        assertThat(decision.limit()).isEqualTo(5L);
        assertThat(decision.remaining()).isEqualTo(4L);
        verify(observability).recordRateLimit("assistant", "allowed");
    }

    @Test
    void rejectedRequestIncludesRetryDelay() {
        RedisCacheService redis = mock(RedisCacheService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        AppProperties properties = new AppProperties();
        when(redis.consumeTokenBucket(
                startsWith("rate:bucket:meal-analysis:"), eq(3L), eq(1L), eq(Duration.ofSeconds(6)), eq(1L)
        )).thenReturn(Optional.of(new RedisCacheService.TokenBucketResult(
                false, 0, Duration.ofMillis(2500))));
        RateLimitService service = new RateLimitService(redis, properties, observability);

        RateLimitService.RateLimitDecision decision = service.consumeMealAnalysis("demo");

        assertThat(decision.status()).isEqualTo(RateLimitService.RateLimitStatus.LIMIT_EXCEEDED);
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofMillis(2500));
        verify(observability).recordRateLimit("meal-analysis", "rejected");
    }

    @Test
    void redisFailureIsNotReportedAsUserLimitExceeded() {
        RedisCacheService redis = mock(RedisCacheService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        AppProperties properties = new AppProperties();
        when(redis.consumeTokenBucket(
                startsWith("rate:bucket:assistant:"), eq(5L), eq(1L), eq(Duration.ofSeconds(3)), eq(1L)
        )).thenReturn(Optional.empty());
        RateLimitService service = new RateLimitService(redis, properties, observability);

        RateLimitService.RateLimitDecision decision = service.consumeAssistant("demo");

        assertThat(decision.status()).isEqualTo(RateLimitService.RateLimitStatus.REDIS_UNAVAILABLE);
        assertThat(decision.unavailable()).isTrue();
        assertThat(decision.allowed()).isFalse();
        verify(observability).recordRateLimit("assistant", "redis_unavailable");
    }
}
