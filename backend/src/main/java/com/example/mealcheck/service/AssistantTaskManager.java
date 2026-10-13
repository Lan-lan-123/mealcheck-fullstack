package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One lifecycle from admission through queueing, execution and cleanup. */
@Service
public class AssistantTaskManager {
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
    private final ConcurrentMap<String, Task> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Task> users = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService deadlines;
    private final Duration timeout;
    @org.springframework.beans.factory.annotation.Autowired
    public AssistantTaskManager(AppProperties properties,
            @org.springframework.beans.factory.annotation.Value("${mealcheck.assistant-delivery.workers:4}") int workers,
            @org.springframework.beans.factory.annotation.Value("${mealcheck.assistant-delivery.queue-capacity:16}") int queue) {
        this(workers, queue, Duration.ofSeconds(Math.max(1, properties.getAiBudget().getOperationTimeoutSeconds())));
    }
    public AssistantTaskManager(int threads, int queue, Duration timeout) {
        this.timeout = timeout;
        this.workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> daemon(r, "assistant-worker"));
        var timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "assistant-deadline"));
        timer.setRemoveOnCancelPolicy(true); this.deadlines = timer;
    }
    private static Thread daemon(Runnable task, String name) { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    public final class Task {
        private final long userId;
        private final AssistantStreamDelivery delivery;
        private final long deadline;
        private final Consumer<State> interrupted;
        private final Runnable completed;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private volatile State state = State.QUEUED;
        private FutureTask<Void> work;
        private ScheduledFuture<?> timer;
        private Task(long userId, AssistantStreamDelivery delivery, long deadline, Consumer<State> interrupted, Runnable completed) {
            this.userId = userId; this.delivery = delivery; this.deadline = deadline; this.interrupted = interrupted; this.completed = completed;
        }
        public State state() { return state; }
        public long deadlineNanos() { return deadline; }
        private synchronized boolean start() {
            if (state != State.QUEUED) return false;
            if (System.nanoTime() >= deadline) { cancel(State.TIMED_OUT); return false; }
            state = State.RUNNING; return true;
        }
        private void check() {
            if (state == State.CANCELLED) throw new CancellationException("回复已取消");
            if (state == State.TIMED_OUT || System.nanoTime() >= deadline) throw new RemoteCallGuard.RemoteCallDeadlineException("AI request deadline exceeded including queue time");
        }
        private synchronized void cancel(State reason) {
            if (state != State.QUEUED && state != State.RUNNING) return;
            boolean queued = state == State.QUEUED; state = reason;
            delivery.failureStatus = reason == State.TIMED_OUT ? "ERROR" : "CANCELLED";
            if (work != null) { work.cancel(true); workers.remove(work); }
            delivery.cancel(reason == State.TIMED_OUT);
            try { interrupted.accept(reason); } finally { if (queued) cleanup(); }
        }
        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) return;
            if (timer != null) timer.cancel(false);
            requests.remove(delivery.requestId, this); users.remove(userId, this); completed.run();
        }
    }
    public Task submit(long userId, AssistantStreamDelivery delivery, Runnable operation, Consumer<State> interrupted, Runnable completed) {
        long deadline = System.nanoTime() + timeout.toNanos();
        var inherited = AiCallContext.capture(); if (inherited != null) deadline = Math.min(deadline, inherited.deadlineNanos());
        var task = new Task(userId, delivery, deadline, interrupted, completed);
        if (users.putIfAbsent(userId, task) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "上一次回复仍在生成或清理，请稍后再试。");
        requests.put(delivery.requestId, task);
        try {
            synchronized (task) {
                task.work = new FutureTask<>(() -> {
                    if (!task.start()) return null;
                    try (var scope = AiCallContext.restore(new AiCallContext.Context(userId, task.deadline, task::check))) {
                        task.check(); operation.run();
                        synchronized (task) { if (task.state == State.RUNNING) task.state = State.SUCCEEDED; }
                    } catch (RuntimeException error) {
                        synchronized (task) { if (task.state == State.RUNNING) task.state = State.FAILED; }
                        throw error;
                    } finally { task.cleanup(); }
                    return null;
                });
                task.timer = deadlines.schedule(() -> task.cancel(State.TIMED_OUT), Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                workers.execute(task.work);
            }
            return task;
        } catch (RejectedExecutionException busy) {
            task.cancel(State.CANCELLED);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "助手繁忙，请稍后再试");
        }
    }
    public void cancel(long userId, String requestId) {
        var task = requests.get(requestId); if (task != null && task.userId == userId) task.cancel(State.CANCELLED);
    }
    public int queuedTasks() { return workers.getQueue().size(); }
    @jakarta.annotation.PreDestroy public void shutdown() {
        requests.values().forEach(task -> task.cancel(State.CANCELLED)); deadlines.shutdownNow(); workers.shutdownNow();
    }
}
