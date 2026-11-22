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
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED }
    private final ConcurrentMap<String, Task> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Task> users = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService deadlines;
    private final Duration timeout;
    private final AssistantTaskStore shared;
    private final String instanceId;
    private final int leaseSeconds;
    private final ScheduledExecutorService heartbeats;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong heartbeatHealthy=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastHeartbeatSuccess=new java.util.concurrent.atomic.AtomicLong();
    @org.springframework.beans.factory.annotation.Autowired
    public AssistantTaskManager(AppProperties properties, AssistantTaskStore shared, io.micrometer.core.instrument.MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${mealcheck.assistant-delivery.workers:4}") int workers,
            @org.springframework.beans.factory.annotation.Value("${mealcheck.assistant-delivery.queue-capacity:16}") int queue,
            @org.springframework.beans.factory.annotation.Value("${mealcheck.assistant-delivery.lease-seconds:15}") int leaseSeconds) {
        this(workers, queue, Duration.ofSeconds(Math.max(1, properties.getAiBudget().getOperationTimeoutSeconds())),shared,
                java.util.UUID.randomUUID().toString(),leaseSeconds);
        io.micrometer.core.instrument.Gauge.builder("mealcheck.assistant.coordination.healthy",heartbeatHealthy,java.util.concurrent.atomic.AtomicLong::get).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("mealcheck.assistant.coordination.last.success.timestamp.seconds",lastHeartbeatSuccess,java.util.concurrent.atomic.AtomicLong::get).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("mealcheck.assistant.delivery.local.active",requests,java.util.Map::size).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("mealcheck.assistant.delivery.local.queued",this.workers,value->value.getQueue().size()).register(metrics);
    }
    public AssistantTaskManager(int threads, int queue, Duration timeout) {
        this(threads,queue,timeout,null,java.util.UUID.randomUUID().toString(),15);
    }
    public AssistantTaskManager(int threads,int queue,Duration timeout,AssistantTaskStore shared,String instanceId,int leaseSeconds) {
        if(leaseSeconds<2 || leaseSeconds>300)throw new IllegalArgumentException("Assistant lease must be 2..300 seconds");
        this.shared=shared;this.instanceId=instanceId;this.leaseSeconds=leaseSeconds;
        this.timeout = timeout;
        this.workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> daemon(r, "assistant-worker"));
        var timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "assistant-deadline"));
        timer.setRemoveOnCancelPolicy(true); this.deadlines = timer;
        heartbeats=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"assistant-heartbeat"));
        if(shared!=null)heartbeats.scheduleWithFixedDelay(this::synchronizeLeases,leaseSeconds*1000L/3,leaseSeconds*1000L/3,TimeUnit.MILLISECONDS);
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
        private AssistantTaskStore.Lease lease;
        private volatile long renewedAt=System.nanoTime();
        private final long admittedAt=System.nanoTime();
        private volatile long executionStartedAt;
        private Task(long userId, AssistantStreamDelivery delivery, long deadline, Consumer<State> interrupted, Runnable completed) {
            this.userId = userId; this.delivery = delivery; this.deadline = deadline; this.interrupted = interrupted; this.completed = completed;
        }
        public State state() { return state; }
        public long deadlineNanos() { return deadline; }
        private synchronized boolean start() {
            if (state != State.QUEUED) return false;
            if (System.nanoTime() >= deadline) { cancel(State.TIMED_OUT); return false; }
            if(shared!=null && !shared.start(lease)) { cancelLocal(State.CANCELLED); return false; }
            state = State.RUNNING; executionStartedAt=System.nanoTime();
            org.slf4j.LoggerFactory.getLogger(AssistantTaskManager.class).info(
                    "Assistant admission timing. requestId={}, elapsedMicros={}, outcome=started",delivery.requestId,(executionStartedAt-admittedAt)/1000);
            return true;
        }
        private void check() {
            if (state == State.CANCELLED || state == State.INTERRUPTED) throw new CancellationException("回复已取消或执行失去所有权");
            if (state == State.TIMED_OUT || System.nanoTime() >= deadline) throw new RemoteCallGuard.RemoteCallDeadlineException("AI request deadline exceeded including queue time");
            if(shared!=null && System.nanoTime()-renewedAt>=TimeUnit.SECONDS.toNanos(leaseSeconds))
                throw new CancellationException("助手执行租约已过期");
        }
        private synchronized void cancel(State reason) {
            if(state!=State.QUEUED && state!=State.RUNNING)return;
            if(shared!=null) {
                try { shared.cancel(userId,delivery.requestId); }
                catch(RuntimeException unavailable) { /* Local interruption remains effective; the shared lease cannot be reused until expiry. */ }
            }
            cancelLocal(reason);
        }
        private synchronized void cancelLocal(State reason) {
            if (state != State.QUEUED && state != State.RUNNING) return;
            boolean queued = state == State.QUEUED; state = reason;
            delivery.failureStatus = reason == State.CANCELLED ? "CANCELLED" : "ERROR";
            if (work != null) { work.cancel(true); workers.remove(work); }
            delivery.cancel(reason != State.CANCELLED);
            try { interrupted.accept(reason); } finally { if (queued) cleanup(); }
        }
        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) return;
            if (timer != null) timer.cancel(false);
            try {
                if(shared!=null)shared.finish(lease,state.name());
            } catch(RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(AssistantTaskManager.class).warn("Assistant cleanup deferred until lease expiry");
            } finally {
                org.slf4j.LoggerFactory.getLogger(AssistantTaskManager.class).info(
                        "Assistant task timing. requestId={}, elapsedMicros={}, executionMicros={}, state={}",
                        delivery.requestId,(System.nanoTime()-admittedAt)/1000,
                        executionStartedAt==0?-1:(System.nanoTime()-executionStartedAt)/1000,state);
                requests.remove(delivery.requestId, this); users.remove(userId, this); completed.run();
            }
        }
    }
    public Task submit(long userId, AssistantStreamDelivery delivery, Runnable operation, Consumer<State> interrupted, Runnable completed) {
        long deadline = System.nanoTime() + timeout.toNanos();
        var inherited = AiCallContext.capture(); if (inherited != null) deadline = Math.min(deadline, inherited.deadlineNanos());
        var task = new Task(userId, delivery, deadline, interrupted, completed);
        if(closed.get())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"助手实例正在退出，请稍后重试。");
        if (users.putIfAbsent(userId, task) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "上一次回复仍在生成或清理，请稍后再试。");
        try {
            if(shared!=null)task.lease=shared.admit(delivery.requestId,userId,instanceId,
                    Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())),leaseSeconds);
        } catch(RuntimeException rejected) { users.remove(userId,task);throw coordinationFailure(rejected); }
        requests.put(delivery.requestId, task);
        try {
            synchronized (task) {
                task.work = new FutureTask<>(() -> {
                    try (var execution=new AssistantExecutionScope(shared,task.lease);
                         var scope = AiCallContext.restore(new AiCallContext.Context(userId, task.deadline, task::check))) {
                        if (!task.start()) return null;
                        task.check(); operation.run();
                        synchronized (task) { if (task.state == State.RUNNING) task.state = State.SUCCEEDED; }
                    } catch (RuntimeException error) {
                        synchronized (task) { if (task.state == State.RUNNING || task.state == State.QUEUED) task.state = State.FAILED; }
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
        // A cancellation may arrive at any instance. Ownership is checked by the database update.
        if(shared!=null) {
            try { shared.cancel(userId,requestId); }
            catch(RuntimeException unavailable) {throw coordinationFailure(unavailable);}
        }
        var task = requests.get(requestId); if (task != null && task.userId == userId) task.cancelLocal(State.CANCELLED);
    }
    public AssistantTaskStore.Status status(long userId,String requestId) {
        try {return shared.status(userId,requestId);}catch(RuntimeException unavailable){throw coordinationFailure(unavailable);}
    }
    public <T> T call(long userId,java.util.function.Function<AssistantStreamDelivery,T> operation) {
        var answer=new CompletableFuture<T>();var delivery=new AssistantStreamDelivery(null);
        var result=new java.util.concurrent.atomic.AtomicReference<T>();var succeeded=new AtomicBoolean();
        var task=submit(userId,delivery,()->{
            try { result.set(operation.apply(delivery));succeeded.set(true); }
            catch(RuntimeException failure) { answer.completeExceptionally(failure);throw failure; }
        },state->answer.completeExceptionally(state==State.TIMED_OUT
                ? new RemoteCallGuard.RemoteCallDeadlineException("Assistant deadline exceeded")
                : new CancellationException("回复已取消")),()->{
            if(!answer.isDone()) {
                if(succeeded.get())answer.complete(result.get());
                else answer.completeExceptionally(new CancellationException("回复未完成"));
            }
        });
        try { return answer.get(timeout.toMillis()+5000,TimeUnit.MILLISECONDS); }
        catch(InterruptedException interrupted) { task.cancel(State.CANCELLED);Thread.currentThread().interrupt();throw new CancellationException("等待已取消"); }
        catch(TimeoutException expired) { task.cancel(State.TIMED_OUT);throw new RemoteCallGuard.RemoteCallDeadlineException("Assistant deadline exceeded"); }
        catch(ExecutionException failure) { if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(failure.getCause()); }
    }
    private void synchronizeLeases() {
        try {
            long renewed=System.nanoTime();
            var snapshot=java.util.List.copyOf(requests.values());
            var live=new java.util.HashMap<String,AssistantTaskStore.Heartbeat>();
            for(var heartbeat:shared.heartbeat(instanceId,snapshot.stream().map(task->task.lease).toList(),leaseSeconds))live.put(heartbeat.requestId(),heartbeat);
            for(var task:snapshot) {
                var heartbeat=live.get(task.delivery.requestId);
                if(heartbeat==null || !heartbeat.token().equals(task.lease.token()))task.cancelLocal(State.INTERRUPTED);
                else {
                    task.renewedAt=renewed;
                    if(heartbeat.state().equals("CANCEL_REQUESTED"))task.cancelLocal(State.CANCELLED);
                }
            }
            shared.reap();
            heartbeatHealthy.set(1);lastHeartbeatSuccess.set(System.currentTimeMillis()/1000);
        } catch(RuntimeException unavailable) {
            heartbeatHealthy.set(0);
            // Do not renew locally on a failed database heartbeat; local checks and publication fencing fail closed.
            for(var task:requests.values())if(System.nanoTime()-task.renewedAt>=TimeUnit.SECONDS.toNanos(leaseSeconds))task.cancelLocal(State.INTERRUPTED);
        }
    }
    private RuntimeException coordinationFailure(RuntimeException failure) {
        return failure instanceof org.springframework.dao.DataAccessException || failure instanceof org.springframework.transaction.TransactionException
                ? new IllegalStateException("助手协调服务暂时不可用，请稍后重试。",failure):failure;
    }
    public int queuedTasks() { return workers.getQueue().size(); }
    @jakarta.annotation.PreDestroy public void shutdown() {
        closed.set(true);
        requests.values().forEach(task -> task.cancel(State.CANCELLED)); deadlines.shutdownNow(); workers.shutdownNow();heartbeats.shutdownNow();
    }
}
