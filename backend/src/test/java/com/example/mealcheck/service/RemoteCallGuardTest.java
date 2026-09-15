package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteCallGuardTest {
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
