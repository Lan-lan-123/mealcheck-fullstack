package com.example.mealcheck.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${mealcheck.analysis.dispatch:rabbitmq}' == 'database' && ${mealcheck.analysis.worker-enabled:true}")
public class MealAnalysisWorker {
    private static final Logger log=LoggerFactory.getLogger(MealAnalysisWorker.class);
    private final MealAnalysisRequestStore store;
    private final MealAnalysisService analysis;
    private final MeterRegistry metrics;
    private final java.util.concurrent.Executor executor;
    private final MealAnalysisLeaseService leases;
    private final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
    public MealAnalysisWorker(MealAnalysisRequestStore store,MealAnalysisService analysis,MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Qualifier("mealAnalysisExecutor") java.util.concurrent.Executor executor,
            MealAnalysisLeaseService leases) {
        this.store=store;this.analysis=analysis;this.metrics=metrics;this.executor=executor;
        this.leases=leases;
    }
    @Scheduled(fixedDelayString="${mealcheck.analysis.poll-ms:1000}")
    public void tick() {
        if (!active.compareAndSet(false,true)) return;
        try { executor.execute(() -> { try { processOne(); } finally { active.set(false); } }); }
        catch (RuntimeException error) { active.set(false); throw error; }
    }
    public void processOne() {
        var work=store.next();
        if (work==null) return;
        long start=System.nanoTime();String outcome="failure";
        String phase="ANALYZE";
        try (var lease=leases.track(work)) {
            var prepared=analysis.prepareValidated(work.user(),work.image(),work.goal());
            phase="SAVE";
            var result=store.complete(work.user(),work.key(),work.token(),prepared);
            outcome="SUCCEEDED".equals(result.status())?"success":"fenced";
        } catch (RuntimeException error) {
            store.fail(work.user().getId(),work.key(),work.token(),phase,error);
            // No image, model response, account or request key in operational logs.
            log.warn("Background meal analysis failed type={}",error.getClass().getSimpleName());
        } finally {
            metrics.counter("mealcheck.analysis.jobs","outcome",outcome).increment();
            Timer.builder("mealcheck.analysis.duration").tag("outcome",outcome).publishPercentileHistogram().register(metrics)
                .record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
        }
    }
}
