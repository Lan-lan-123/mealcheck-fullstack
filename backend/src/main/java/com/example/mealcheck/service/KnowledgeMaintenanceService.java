package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KnowledgeMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeMaintenanceService.class);
    private final KnowledgeRebuildStore store;
    private final KnowledgeEmbeddingService embeddings;
    private final PgVectorKnowledgeService vectors;
    private final KnowledgeIndexService index;
    private final AppProperties properties;
    private final Executor executor;
    private final MeterRegistry metrics;
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean sourceIndexed;
    private boolean rebuildRequested;

    public KnowledgeMaintenanceService(KnowledgeRebuildStore store, KnowledgeEmbeddingService embeddings,
            PgVectorKnowledgeService vectors, KnowledgeIndexService index, AppProperties properties,
            @Qualifier("knowledgeMaintenanceExecutor") Executor executor, MeterRegistry metrics) {
        this.store=store; this.embeddings=embeddings; this.vectors=vectors; this.index=index;
        this.properties=properties; this.executor=executor; this.metrics=metrics;
    }

    @Scheduled(fixedDelayString="${mealcheck.knowledge.maintenance-poll-ms:5000}", initialDelayString="${mealcheck.knowledge.maintenance-initial-delay-ms:1000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { processBatch(); }
                catch (RuntimeException error) { log.warn("Knowledge maintenance deferred type={}", error.getClass().getSimpleName()); }
                finally { running.set(false); }
            });
        } catch (RejectedExecutionException rejected) { running.set(false); }
    }

    public long requestRebuild() {
        return store.request(vectors.configuredSignature(), vectors.embeddingDimension(),
                properties.getKnowledge().getVectorStorage() != null
                        && "vector".equalsIgnoreCase(properties.getKnowledge().getVectorStorage().trim()) ? "vector" : "halfvec");
    }
    public void retry(long id) { store.retry(id, vectors.configuredSignature()); }

    public void processBatch() {
        // Automatic maintenance only submits new jobs once per startup. FAILED jobs
        // require an explicit admin retry and retain all successfully staged chunks.
        if (properties.getKnowledge().isAutoIndex() && !sourceIndexed) {
            if (!vectors.storageMatchesConfiguration() || vectors.hasStaleEmbeddings()) {
                if (!rebuildRequested) { requestRebuild(); rebuildRequested = true; }
            }
            else {
                index.reindex();
                sourceIndexed = true;
            }
        }
        var work = store.claim(vectors.configuredSignature());
        if (work == null) return;
        try {
            for (var chunk : store.pending(work, 5)) {
                String text = chunk.title() == null || chunk.title().isBlank() ? chunk.content() : chunk.title().trim()+"\n\n"+chunk.content().trim();
                String vector = embeddings.toPgVector(embeddings.embed(text));
                if (!store.stage(work, chunk, vector)) return;
            }
            if (store.activate(work)) {
                index.clearSearchCache();
                metrics.counter("mealcheck.knowledge.rebuild", "outcome", "success").increment();
            } else store.release(work, false);
        } catch (RuntimeException error) {
            store.release(work, true);
            metrics.counter("mealcheck.knowledge.rebuild", "outcome", "failure").increment();
            log.warn("Knowledge rebuild deferred jobId={} type={}", work.id(), error.getClass().getSimpleName());
        }
    }
}
