package com.example.mealcheck.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;

@Service
public class AiCallConcurrency {
    private final AiCallPermitStore store;
    private final MeterRegistry metrics;
    private final int limit;
    public AiCallConcurrency(AiCallPermitStore store, MeterRegistry metrics,
            @Value("${mealcheck.ai-budget.max-concurrent-calls:10}") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("AI global concurrency must be 1..100");
        this.store=store; this.metrics=metrics; this.limit=limit;
    }
    public UUID acquire(Duration remaining) {
        long deadline=System.nanoTime()+remaining.toNanos();
        long backoff=50;
        try {
            while (System.nanoTime()<deadline) {
                AiCallContext.checkExecution();
                // Conservative crash lease exceeds the client's remaining deadline. No DB transaction is held while waiting.
                var context=AiCallContext.capture();
                var admission=store.tryAcquire(limit, Math.max(1000,(deadline-System.nanoTime())/1_000_000)+30000,
                        context != null && context.background());
                if (admission != null && admission.permit()!=null) return admission.permit();
                String reason=admission == null ? "concurrency" : admission.reason();
                metrics.counter("mealcheck.ai.admission.waits","reason",reason).increment();
                long wait=admission != null && admission.retryMillis()>0 ? Math.min(1000,admission.retryMillis())
                        : java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,backoff/2),backoff+1);
                long left=(deadline-System.nanoTime())/1_000_000;
                if (left<=0) break;
                Thread.sleep(Math.min(wait,left));
                backoff=Math.min(1000,backoff*2);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RemoteCallGuard.RemoteCallRejectedException("AI concurrency wait interrupted");
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new AiBudgetService.BudgetUnavailableException();
        }
        metrics.counter("mealcheck.ai.concurrency.rejected").increment();
        throw new RemoteCallGuard.RemoteCallDeadlineException("AI concurrency wait exceeded operation deadline");
    }
    public void release(UUID id) {
        if (id==null) return;
        try { store.release(id); }
        catch (org.springframework.dao.DataAccessException unavailable) {
            // Keep the bounded lease counted until it expires; never fail a completed model result.
            metrics.counter("mealcheck.ai.concurrency.release.deferred").increment();
        }
    }
}
