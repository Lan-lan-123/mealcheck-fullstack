package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class RemoteCallGuard {
    private final AppProperties properties;
    private final ConcurrentHashMap<String, CallState> states = new ConcurrentHashMap<>();

    public RemoteCallGuard(AppProperties properties) {
        this.properties = properties;
    }

    public <T> T execute(String name, Supplier<T> remoteCall) {
        String safeName = name == null || name.isBlank() ? "remote" : name.trim();
        CallState state = states.computeIfAbsent(safeName,
                ignored -> new CallState(maxConcurrentCalls()));
        long now = System.nanoTime();
        if (state.openUntilNanos.get() > now) {
            throw new RemoteCallRejectedException("Remote circuit is open: " + safeName);
        }
        if (!state.bulkhead.tryAcquire()) {
            throw new RemoteCallRejectedException("Remote concurrency limit reached: " + safeName);
        }

        try {
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= maxAttempts(); attempt++) {
                try {
                    T result = remoteCall.get();
                    state.consecutiveFailures.set(0);
                    state.openUntilNanos.set(0);
                    return result;
                } catch (RemoteCallRejectedException e) {
                    throw e;
                } catch (NonRetryableRemoteCallException e) {
                    throw e;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    if (attempt < maxAttempts()) {
                        backoff(attempt);
                    }
                }
            }
            int failures = state.consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold()) {
                state.openUntilNanos.set(System.nanoTime() + openDuration().toNanos());
                state.consecutiveFailures.set(0);
            }
            throw lastFailure == null
                    ? new IllegalStateException("Remote call failed: " + safeName)
                    : lastFailure;
        } finally {
            state.bulkhead.release();
        }
    }

    private void backoff(int attempt) {
        long delay = Math.min(5000L, initialBackoffMillis() * (1L << Math.min(10, attempt - 1)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCallRejectedException("Remote retry was interrupted");
        }
    }

    private int maxAttempts() {
        return Math.max(1, Math.min(5, properties.getRemoteResilience().getMaxAttempts()));
    }

    private long initialBackoffMillis() {
        return Math.max(10L, Math.min(5000L, properties.getRemoteResilience().getInitialBackoffMillis()));
    }

    private int failureThreshold() {
        return Math.max(1, Math.min(100, properties.getRemoteResilience().getFailureThreshold()));
    }

    private Duration openDuration() {
        return Duration.ofSeconds(Math.max(1,
                Math.min(3600, properties.getRemoteResilience().getOpenDurationSeconds())));
    }

    private int maxConcurrentCalls() {
        return Math.max(1, Math.min(100, properties.getRemoteResilience().getMaxConcurrentCalls()));
    }

    private static final class CallState {
        private final Semaphore bulkhead;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openUntilNanos = new AtomicLong();

        private CallState(int maxConcurrentCalls) {
            this.bulkhead = new Semaphore(maxConcurrentCalls);
        }
    }

    public static class RemoteCallRejectedException extends RuntimeException {
        public RemoteCallRejectedException(String message) {
            super(message);
        }
    }

    public static class NonRetryableRemoteCallException extends RuntimeException {
        public NonRetryableRemoteCallException(String message) {
            super(message);
        }
    }
}
