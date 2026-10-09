package com.example.mealcheck.service;

import java.time.Duration;

/** Explicitly propagated to bounded executors; never uses inheritable thread locals. */
public final class AiCallContext {
    public record Context(Long userId, long deadlineNanos, Runnable dispatchGuard) implements java.io.Serializable {
        public Context(Long userId, long deadlineNanos) { this(userId, deadlineNanos, null); }
    }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private AiCallContext() { }
    public static Context capture() { return CURRENT.get(); }
    public static Scope restore(Context context) {
        Context previous = CURRENT.get();
        if (context == null) CURRENT.remove(); else CURRENT.set(context);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static Scope open(Long userId, Duration timeout) {
        Context current = capture();
        long deadline = System.nanoTime() + timeout.toNanos();
        if (current != null && java.util.Objects.equals(current.userId(), userId))
            deadline = Math.min(deadline, current.deadlineNanos());
        return restore(new Context(userId, deadline, current == null ? null : current.dispatchGuard()));
    }
    public static Scope systemOperation(Duration timeout) {
        return restore(new Context(null,System.nanoTime()+timeout.toNanos()));
    }
    public static Scope systemOperation(Duration timeout, Runnable dispatchGuard) {
        return restore(new Context(null,System.nanoTime()+timeout.toNanos(),dispatchGuard));
    }
    public static void checkExecution() {
        var context = capture();
        if (context != null && context.dispatchGuard() != null) context.dispatchGuard().run();
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
