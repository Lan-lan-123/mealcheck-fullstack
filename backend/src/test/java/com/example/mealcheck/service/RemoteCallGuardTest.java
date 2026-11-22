package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteCallGuardTest {
    @Test void reviewDoesNotRetryWithLessThanItsReservedExecutionWindow() {
        var properties=new AppProperties();properties.getRemoteResilience().setMaxAttempts(2);
        var calls=new AtomicInteger();var guard=new RemoteCallGuard(properties);
        try(var context=AiCallContext.open(1L,java.time.Duration.ofSeconds(2))) {
            assertThatThrownBy(()->guard.execute("diet-assistant-evidence-review",()->{calls.incrementAndGet();throw new IllegalStateException(new java.io.IOException());}))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void localAdmissionDeadlinesDoNotTripProviderCircuit() {
        var properties=new AppProperties();properties.getRemoteResilience().setFailureThreshold(1);
        var guard=new RemoteCallGuard(properties);
        assertThatThrownBy(()->guard.execute("queued",()->{throw new RemoteCallGuard.LocalAdmissionDeadlineException("queue expired");}))
                .isInstanceOf(RemoteCallGuard.LocalAdmissionDeadlineException.class);
        assertThat(guard.execute("queued",()->"available")).isEqualTo("available");
    }
    @Test void deadlineFailuresTripCircuitAndAreCountedOncePerOperation() {
        var properties = new AppProperties();
        properties.getRemoteResilience().setFailureThreshold(2);
        var guard = new RemoteCallGuard(properties);
        var calls = new AtomicInteger();
        for (int index=0; index<2; index++) {
            assertThatThrownBy(() -> guard.execute("slow", () -> {
                calls.incrementAndGet();
                throw new RemoteCallGuard.RemoteCallDeadlineException("AI operation deadline exceeded");
            })).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        assertThatThrownBy(() -> guard.execute("slow", calls::incrementAndGet))
                .isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class).hasMessageContaining("circuit");
        assertThat(calls).hasValue(2);
    }

    @Test void deadlineExpiryDuringBackoffAlsoTripsCircuit() {
        var properties = new AppProperties();
        properties.getRemoteResilience().setFailureThreshold(1);
        var guard = new RemoteCallGuard(properties);
        var calls = new AtomicInteger();
        try (var scope = AiCallContext.open(1L, java.time.Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> guard.execute("retry-after-timeout", () -> {
                calls.incrementAndGet();
                throw new RemoteCallGuard.RetryableRemoteCallException(429, java.time.Duration.ofSeconds(3));
            })).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        assertThatThrownBy(() -> guard.execute("retry-after-timeout", calls::incrementAndGet))
                .isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class).hasMessageContaining("circuit");
        assertThat(calls).hasValue(1);
    }

    @Test void localRejectionsDoNotTripCircuit() {
        var properties = new AppProperties();
        properties.getRemoteResilience().setFailureThreshold(1);
        var guard = new RemoteCallGuard(properties);
        assertThatThrownBy(() -> guard.execute("local", () -> {
            throw new RemoteCallGuard.RemoteCallRejectedException("local admission rejected");
        })).isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class);
        assertThat(guard.execute("local", () -> "ok")).isEqualTo("ok");
    }

    @Test void inFlightSuccessDoesNotCloseAnotherCallsNewlyOpenedCircuit() throws Exception {
        var properties = new AppProperties();
        properties.getRemoteResilience().setFailureThreshold(1);
        var guard = new RemoteCallGuard(properties);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> guard.execute("shared", () -> {
                entered.countDown();
                try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
                return "older success";
            }));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> guard.execute("shared", () -> {
                throw new RemoteCallGuard.RemoteCallDeadlineException("timed out");
            })).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
            release.countDown();
            assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("older success");
            assertThatThrownBy(() -> guard.execute("shared", () -> "must not dispatch"))
                    .isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class).hasMessageContaining("circuit");
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void expiredOuterOperationCannotDispatchOrRenewItsDeadline() {
        var guard = new RemoteCallGuard(new AppProperties());
        var calls = new AtomicInteger();
        var expired = new AiCallContext.Context(1L, System.nanoTime() - 1);
        try (var outer = AiCallContext.restore(expired);
             var nested = AiCallContext.open(1L, java.time.Duration.ofSeconds(120))) {
            assertThat(AiCallContext.capture().deadlineNanos()).isEqualTo(expired.deadlineNanos());
            assertThatThrownBy(() -> guard.execute("expired", calls::incrementAndGet))
                    .isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
            assertThat(calls).hasValue(0);
        }
        assertThat(AiCallContext.capture()).isNull();
    }
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
