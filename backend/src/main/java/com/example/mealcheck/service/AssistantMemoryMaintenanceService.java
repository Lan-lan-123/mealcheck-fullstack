package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AssistantMemoryMaintenanceService {
    private final AssistantMemoryStore store;
    private final KnowledgeEmbeddingService embeddings;
    private final VectorCompressionService compression;
    private final AppProperties properties;
    private final Executor executor;
    private final MeterRegistry metrics;
    private final AtomicBoolean running=new AtomicBoolean();
    public AssistantMemoryMaintenanceService(AssistantMemoryStore store,KnowledgeEmbeddingService embeddings,
            VectorCompressionService compression,AppProperties properties,
            @Qualifier("assistantMemoryMaintenanceExecutor") Executor executor,MeterRegistry metrics) {
        this.store=store;this.embeddings=embeddings;this.compression=compression;this.properties=properties;this.executor=executor;this.metrics=metrics;
    }
    public String signature() { return embeddings.signature()+":"+compression.signatureSuffix(); }
    public long request() { return store.request(signature(),properties.getKnowledge().getEmbeddingDim(),compression.storageType().sqlName()); }
    public void retry(long id) { store.retry(id,signature()); }
    @Scheduled(fixedDelayString="${mealcheck.assistant-memory.maintenance-poll-ms:5000}",initialDelayString="${mealcheck.assistant-memory.initial-delay-ms:5000}")
    public void tick() {
        if (!running.compareAndSet(false,true)) return;
        try { executor.execute(()->{
            try { processBatch(); }
            catch (RuntimeException error) { metrics.counter("mealcheck.assistant.memory.rebuild","outcome","deferred").increment(); }
            finally { running.set(false); }
        }); } catch (RejectedExecutionException rejected) { running.set(false); }
    }
    public void processBatch() {
        if (store.needsRebuild(signature())) request();
        var work=store.claim(signature());
        if (work==null) return;
        try (var scope=AiCallContext.systemOperation(Duration.ofSeconds(properties.getAiBudget().getOperationTimeoutSeconds()))) {
            for (var fact:store.pending(work,5)) {
                String vector=embeddings.toPgVector(embeddings.embed(fact.text()));
                if (!store.stage(work,fact,vector)) return;
            }
            if (store.activate(work)) metrics.counter("mealcheck.assistant.memory.rebuild","outcome","success").increment();
            else store.release(work,false);
        } catch (RuntimeException error) {
            store.release(work,true);
            metrics.counter("mealcheck.assistant.memory.rebuild","outcome","failure").increment();
        }
    }
}
