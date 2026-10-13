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
        return execute(name, Duration.ofSeconds(Math.max(1, properties.getRemoteResilience().getTotalTimeoutSeconds())),
                ignored -> remoteCall.get());
    }

    /** Each HTTP attempt receives the remaining budget, including prior attempts and backoff. */
    public <T> T execute(String name, Duration attemptTimeout, java.util.function.Function<Duration,T> remoteCall) {
        AiCallContext.checkExecution();
        String safeName = name == null || name.isBlank() ? "remote" : name.trim();
        CallState state = states.computeIfAbsent(safeName,
                ignored -> new CallState(maxConcurrentCalls()));
        long now = System.nanoTime();
        long deadline = now + Duration.ofSeconds(Math.max(1, properties.getRemoteResilience().getTotalTimeoutSeconds())).toNanos();
        var context = AiCallContext.capture();
        if (context != null) deadline = Math.min(deadline, context.deadlineNanos());
        if (state.openUntilNanos.get() > now) {
            throw new RemoteCallRejectedException("Remote circuit is open: " + safeName);
        }
        if (!state.bulkhead.tryAcquire()) {
            throw new RemoteCallRejectedException("Remote concurrency limit reached: " + safeName);
        }

        try {
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= maxAttempts(); attempt++) {
                checkInterrupted();
                Duration remaining = remaining(deadline);
                try {
                    T result = remoteCall.apply(remaining.compareTo(attemptTimeout) < 0 ? remaining : attemptTimeout);
                    remaining(deadline);
                    state.consecutiveFailures.set(0);
                    state.openUntilNanos.set(0);
                    return result;
                } catch (RemoteCallRejectedException e) {
                    throw e;
                } catch (java.util.concurrent.CancellationException e) {
                    throw e;
                } catch (NonRetryableRemoteCallException e) {
                    throw e;
                } catch (RuntimeException e) {
                    lastFailure = e;
                    if (attempt < maxAttempts()) {
                        backoff(attempt, e instanceof RetryableRemoteCallException retryable ? retryable.retryAfter() : null, deadline);
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

    private void backoff(int attempt, Duration retryAfter, long deadline) {
        long cap = Math.min(5000L, initialBackoffMillis() * (1L << Math.min(10, attempt - 1)));
        long delay = java.util.concurrent.ThreadLocalRandom.current().nextLong(cap + 1);
        if (retryAfter!=null && retryAfter.compareTo(remaining(deadline))>=0)
            throw new RemoteCallDeadlineException("Provider retry delay exceeds the remaining AI deadline",retryAfter);
        if (retryAfter != null) delay = Math.max(delay, Math.max(0L, retryAfter.toMillis()));
        if (Duration.ofMillis(delay).compareTo(remaining(deadline)) >= 0)
            throw new RemoteCallDeadlineException("Provider retry delay exceeds the remaining AI deadline",retryAfter);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteCallRejectedException("Remote retry was interrupted");
        }
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new RemoteCallDeadlineException("AI operation deadline exceeded");
        return Duration.ofNanos(nanos);
    }
    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new RemoteCallRejectedException("AI operation was interrupted");
    }
    public static Duration parseRetryAfter(String value, java.time.Instant now) {
        if (value == null || value.isBlank()) return null;
        try { return Duration.ofSeconds(Math.max(0L, Long.parseLong(value.trim()))); }
        catch (RuntimeException ignored) {
            try {
                var date = java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(now, date);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException invalid) { return null; }
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
    public static class RetryableRemoteCallException extends RuntimeException {
        private final Duration retryAfter;
        public RetryableRemoteCallException(int status, Duration retryAfter) {
            super("AI provider returned HTTP " + status); this.retryAfter=retryAfter;
        }
        public Duration retryAfter() { return retryAfter; }
    }
    public static class RemoteCallDeadlineException extends RemoteCallRejectedException {
        private final Duration retryAfter;
        public RemoteCallDeadlineException(String message) { this(message,null); }
        public RemoteCallDeadlineException(String message,Duration retryAfter) { super(message);this.retryAfter=retryAfter; }
        public Duration retryAfter() { return retryAfter; }
    }
}
