package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheServiceTest {

    @Test
    void jitteredJsonTtlStaysInsideConfiguredWindow() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        RedisCacheService service = new RedisCacheService(template, new ObjectMapper());

        service.setJsonWithJitter("stats", List.of("value"), Duration.ofSeconds(60), Duration.ofSeconds(15));

        org.mockito.ArgumentCaptor<Duration> ttl = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("stats"), any(String.class), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(60), Duration.ofSeconds(75));
    }

    @Test
    void getAndDeleteRequiredConsumesValueAtomically() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.getAndDelete("captcha:test-id")).thenReturn("12");
        RedisCacheService service = new RedisCacheService(template, new ObjectMapper());

        Optional<String> result = service.getAndDeleteRequired("captcha:test-id");

        assertThat(result).contains("12");
        verify(values).getAndDelete("captcha:test-id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementUsesAtomicLuaScriptWithTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(DefaultRedisScript.class), eq(List.of("rate:test")), eq("60000")))
                .thenReturn(2L);
        RedisCacheService service = new RedisCacheService(template, new ObjectMapper());

        long value = service.increment("rate:test", Duration.ofMinutes(1));

        assertThat(value).isEqualTo(2L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tokenBucketUsesAtomicLuaScriptAndReturnsDecision() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(
                any(DefaultRedisScript.class),
                eq(List.of("rate:bucket:test")),
                eq("5"),
                eq("1"),
                eq("3000"),
                eq("1"),
                eq("30000"),
                eq("1000000")
        )).thenReturn(List.of(1L, 4L, 0L));
        RedisCacheService service = new RedisCacheService(template, new ObjectMapper());

        RedisCacheService.TokenBucketResult result = service.consumeTokenBucket(
                "rate:bucket:test", 5, 1, Duration.ofSeconds(3), 1).orElseThrow();

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(4L);
        assertThat(result.retryAfter()).isZero();
    }

    @Test
    void singleFlightCoalescesConcurrentColdLoads() throws Exception {
        RedisCacheService service = new RedisCacheService(mock(StringRedisTemplate.class), new ObjectMapper());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try {
            Future<String> first = executor.submit(() -> service.singleFlight("cache-key", () -> {
                loads.incrementAndGet();
                loaderStarted.countDown();
                try {
                    releaseLoader.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return "fresh";
            }, Optional.empty()));
            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

            executor.submit(() -> {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                releaseLoader.countDown();
            });
            String follower = service.singleFlight("cache-key", () -> "duplicate", Optional.empty());

            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("fresh");
            assertThat(follower).isEqualTo("fresh");
            assertThat(loads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void singleFlightLetsFollowersUseStaleValueDuringRefresh() throws Exception {
        RedisCacheService service = new RedisCacheService(mock(StringRedisTemplate.class), new ObjectMapper());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        try {
            Future<String> refresh = executor.submit(() -> service.singleFlight("cache-key", () -> {
                loaderStarted.countDown();
                try {
                    releaseLoader.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return "fresh";
            }, Optional.empty()));
            assertThat(loaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

            String follower = service.singleFlight("cache-key", () -> "duplicate", Optional.of("stale"));
            assertThat(follower).isEqualTo("stale");
            releaseLoader.countDown();
            assertThat(refresh.get(1, TimeUnit.SECONDS)).isEqualTo("fresh");
        } finally {
            executor.shutdownNow();
        }
    }
}
