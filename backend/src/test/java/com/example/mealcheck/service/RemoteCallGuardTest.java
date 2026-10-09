package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteCallGuardTest {
    @Test
    void remainingDeadlineShrinksAcrossAttemptsAndRetryAfterDoesNotRetryEarly() {
        AppProperties properties=new AppProperties();
        RemoteCallGuard guard=new RemoteCallGuard(properties);
        AtomicInteger calls=new AtomicInteger();
        try (var scope=AiCallContext.open(1L,java.time.Duration.ofMillis(100))) {
            assertThatThrownBy(()->guard.execute("retry-after",java.time.Duration.ofSeconds(30),remaining->{
                calls.incrementAndGet();
                assertThat(remaining).isLessThan(java.time.Duration.ofMillis(101));
                throw new RemoteCallGuard.RetryableRemoteCallException(429,java.time.Duration.ofSeconds(3));
            })).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        assertThat(calls).hasValue(1);
        assertThat(AiCallContext.capture()).isNull();
    }
    @Test
    void parsesSecondsAndHttpDateRetryAfter() {
        var now=java.time.Instant.parse("2026-10-04T00:00:00Z");
        assertThat(RemoteCallGuard.parseRetryAfter("3",now)).isEqualTo(java.time.Duration.ofSeconds(3));
        assertThat(RemoteCallGuard.parseRetryAfter("Sun, 04 Oct 2026 00:00:05 GMT",now)).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(RemoteCallGuard.parseRetryAfter("invalid",now)).isNull();
    }
    @Test
    void interruptedOperationNeverStartsAnotherRemoteCall() {
        var guard=new RemoteCallGuard(new AppProperties());
        AtomicInteger calls=new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->guard.execute("interrupted",()->calls.incrementAndGet()))
                    .isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class);
            assertThat(calls).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(guard.execute("interrupted",()->"next")).isEqualTo("next");
    }
    @Test
    void retriesTransientFailureThenReturnsResult() {
        AppProperties properties = new AppProperties();
        properties.getRemoteResilience().setMaxAttempts(2);
        properties.getRemoteResilience().setInitialBackoffMillis(10);
        RemoteCallGuard guard = new RemoteCallGuard(properties);
        AtomicInteger calls = new AtomicInteger();

        String result = guard.execute("embedding", () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void opensCircuitAfterConfiguredConsecutiveFailures() {
        AppProperties properties = new AppProperties();
        properties.getRemoteResilience().setMaxAttempts(1);
        properties.getRemoteResilience().setFailureThreshold(2);
        RemoteCallGuard guard = new RemoteCallGuard(properties);
        AtomicInteger calls = new AtomicInteger();

        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> guard.execute("reranker", () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("down");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThatThrownBy(() -> guard.execute("reranker", () -> {
            calls.incrementAndGet();
            return "never";
        })).isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryNonRetryableClientFailure() {
        AppProperties properties = new AppProperties();
        properties.getRemoteResilience().setMaxAttempts(3);
        RemoteCallGuard guard = new RemoteCallGuard(properties);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.execute("ai-chat", () -> {
            calls.incrementAndGet();
            throw new RemoteCallGuard.NonRetryableRemoteCallException("bad request");
        })).isInstanceOf(RemoteCallGuard.NonRetryableRemoteCallException.class);

        assertThat(calls).hasValue(1);
    }
}
