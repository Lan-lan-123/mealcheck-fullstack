package com.example.mealcheck.service;

import java.time.Duration;

/** Explicitly propagated to bounded executors; never uses inheritable thread locals. */
public final class AiCallContext {
    public record Context(Long userId, long deadlineNanos, Runnable dispatchGuard, boolean background) implements java.io.Serializable {
        public Context(Long userId, long deadlineNanos, Runnable dispatchGuard) { this(userId, deadlineNanos, dispatchGuard, false); }
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
        return restore(new Context(userId, deadline, current == null ? null : current.dispatchGuard(), current != null && current.background()));
    }
    public static Scope systemOperation(Duration timeout) {
        return restore(new Context(null,System.nanoTime()+timeout.toNanos(),null,true));
    }
    public static Scope systemOperation(Duration timeout, Runnable dispatchGuard) {
        return restore(new Context(null,System.nanoTime()+timeout.toNanos(),dispatchGuard,true));
    }
    public static Scope background() {
        var context = capture();
        return context == null ? () -> {} : restore(new Context(context.userId(), context.deadlineNanos(), context.dispatchGuard(), true));
    }
    public static void checkExecution() {
        var context = capture();
        if (context != null && System.nanoTime() >= context.deadlineNanos())
            throw new RemoteCallGuard.RemoteCallDeadlineException("AI operation deadline exceeded");
        if (context != null && context.dispatchGuard() != null) context.dispatchGuard().run();
    }
    /** Reject late publication even when the transaction finishes after its caller returns. */
    public static void guardTransactionCommit() {
        checkExecution();
        var context = capture();
        if (context == null || !org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        try (var scope = restore(context)) { checkExecution(); }
                    }
                });
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
