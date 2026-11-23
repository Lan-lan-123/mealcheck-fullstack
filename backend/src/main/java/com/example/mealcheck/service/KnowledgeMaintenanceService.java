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
    private KnowledgeJobLifecycleService lifecycle;
    private KnowledgeEmbeddingProfileStore profiles;
    private KnowledgeRebuildPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreviews(KnowledgeRebuildPreviewService value) { previews=value; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setProfiles(KnowledgeEmbeddingProfileStore profiles) { this.profiles=profiles; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setLifecycle(KnowledgeJobLifecycleService lifecycle) { this.lifecycle=lifecycle; }

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
    public void retry(long id) {
        if(lifecycle!=null) { lifecycle.retry(id);return; }
        String mode = (String) store.status(id).get("embedding_mode");
        String suffix = ":" + ("vector".equalsIgnoreCase(properties.getKnowledge().getVectorStorage()) ? "vector-v1" : "halfvec-v1");
        store.retry(id, mode == null ? vectors.configuredSignature() : embeddings.signatureForMode(mode) + suffix);
    }

    public void processBatch() {
        if(lifecycle!=null) lifecycle.reconcile();
        // Automatic maintenance only submits new jobs once per startup. FAILED jobs
        // require an explicit admin retry and retain all successfully staged chunks.
        if (properties.getKnowledge().isAutoIndex() && !sourceIndexed) {
            if (!vectors.storageMatchesConfiguration() || vectors.hasStaleEmbeddings()) {
                if (!rebuildRequested) {
                    try { requestRebuild(); }
                    catch (org.springframework.web.server.ResponseStatusException busy) {
                        if (busy.getStatusCode().value() != 409) throw busy;
                    }
                    rebuildRequested = true;
                }
            }
            else {
                try {index.initialize();sourceIndexed=true;}
                catch(KnowledgeSourceSyncStore.Conflict deferred) {
                    if(deferred.reason()==KnowledgeSourceSyncStore.Reason.REVIEW_REQUIRED || deferred.reason()==KnowledgeSourceSyncStore.Reason.EDITED) {
                        sourceIndexed=true;
                        log.warn("Knowledge source initialization requires admin review reason={}",deferred.reason());
                    }
                    // Contention is expected. The next maintenance tick retries busy or expired leases.
                    return;
                }
            }
        }
        var work = store.claim(vectors.configuredSignature());
        if (work == null) {
            String suffix = ":" + ("vector".equalsIgnoreCase(properties.getKnowledge().getVectorStorage()) ? "vector-v1" : "halfvec-v1");
            work = store.claimRequested(embeddings.signatureForMode("LOCAL") + suffix,
                    embeddings.isSemanticAvailable() ? embeddings.signatureForMode("SEMANTIC") + suffix : "unavailable");
        }
        if (work == null) return;
        try {
            if(previews!=null&&work.sourceSynced()) previews.verifySelected(work);
            if (!work.sourceSynced()) {
                // Bring the source bundle up to date using the still-active provider.
                // Retrieval keeps using that provider until atomic vector activation.
                var guard=previews==null?null:previews.verifyBeforeSourceSync(work);
                if(guard!=null) index.reindex(work.splitterStrategy(),guard.revision(),work,guard.corpusSha256());
                else if (work.splitterStrategy() == null) index.reindex();
                else index.reindex(work.splitterStrategy());
                if (!store.sourceSynced(work)) return;
            }
            for (var chunk : store.pending(work, 5)) {
                if(lifecycle!=null) {
                    lifecycle.reconcile();
                    if(!store.canContinue(work)) return;
                }
                String text = chunk.title() == null || chunk.title().isBlank() ? chunk.content() : chunk.title().trim()+"\n\n"+chunk.content().trim();
                var embedding = work.profileId()!=null
                        ? embeddings.embedWithProfileWithUsage(text,profiles.find(work.profileId()).profile())
                        : embeddings.embedForSignatureWithUsage(text,work.signature());
                String vector = embeddings.toPgVector(embedding.vector());
                if (!store.stage(work, chunk, vector, embedding.inputTokens(), embedding.totalTokens())) return;
            }
            if(lifecycle!=null) {
                lifecycle.reconcile();
                if(!store.canContinue(work)) return;
            }
            if (store.activate(work)) {
                index.clearSearchCache();
                metrics.counter("mealcheck.knowledge.rebuild", "outcome", "success").increment();
            } else store.release(work, false);
        } catch (RuntimeException error) {
            store.fail(work,KnowledgeJobFailure.classify(error));
            metrics.counter("mealcheck.knowledge.rebuild", "outcome", "failure").increment();
            log.warn("Knowledge rebuild deferred jobId={} type={}", work.id(), error.getClass().getSimpleName());
        }
    }
}
