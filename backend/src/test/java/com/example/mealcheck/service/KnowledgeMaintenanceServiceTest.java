package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeMaintenanceServiceTest {
    @Test void sourceSynchronizationUsesThePersistedJobSplitter() {
        var work = new KnowledgeRebuildStore.Work(7L,"new",2,"halfvec",UUID.randomUUID(),"LOCAL",false,null,"structural-markdown");
        when(store.claim("new")).thenReturn(work);
        when(store.sourceSynced(work)).thenReturn(false);
        service.processBatch();
        verify(index).reindex("structural-markdown");
        verify(index,never()).reindex();
    }
    final KnowledgeRebuildStore store=mock(KnowledgeRebuildStore.class);
    final KnowledgeEmbeddingService embeddings=mock(KnowledgeEmbeddingService.class);
    final PgVectorKnowledgeService vectors=mock(PgVectorKnowledgeService.class);
    final KnowledgeIndexService index=mock(KnowledgeIndexService.class);
    final AppProperties properties=new AppProperties();
    final KnowledgeMaintenanceService service=new KnowledgeMaintenanceService(store,embeddings,vectors,index,properties,Runnable::run,new SimpleMeterRegistry());
    final KnowledgeRebuildStore.Work work=new KnowledgeRebuildStore.Work(1,"new",2,"halfvec",UUID.randomUUID());
    KnowledgeMaintenanceServiceTest() {
        properties.getKnowledge().setAutoIndex(false);
        when(vectors.configuredSignature()).thenReturn("new");
        when(store.claim("new")).thenReturn(work);
    }
    @Test void resumesStagedChunksWithoutCallingProviderAgain() {
        when(store.pending(work,5)).thenReturn(List.of());
        when(store.activate(work)).thenReturn(true);
        service.processBatch();
        verifyNoInteractions(embeddings);
        verify(index).clearSearchCache();
    }
    @Test void cancelledOrInvalidatedWorkerStopsBeforeCallingProvider() {
        var lifecycle=mock(KnowledgeJobLifecycleService.class);
        service.setLifecycle(lifecycle);
        when(store.pending(work,5)).thenReturn(List.of(new KnowledgeRebuildStore.Chunk(7,"title","content","hash")));
        when(store.canContinue(work)).thenReturn(false);
        service.processBatch();
        verifyNoInteractions(embeddings);
        verify(store,never()).stage(any(),any(),anyString(),any(),any());
        verify(store,never()).activate(any());
    }
    @Test void requestedModeSynchronizesSourceAndStagesTargetBeforeActivation() {
        var target = new KnowledgeRebuildStore.Work(2,"semantic",512,"halfvec",UUID.randomUUID(),"SEMANTIC",false);
        var chunk = new KnowledgeRebuildStore.Chunk(7,"title","content","hash");
        when(store.claim("new")).thenReturn(target);
        when(store.sourceSynced(target)).thenReturn(true);
        when(store.pending(target,5)).thenReturn(List.of(chunk));
        when(embeddings.embedForSignatureWithUsage("title\n\ncontent","semantic")).thenReturn(new KnowledgeEmbeddingService.EmbeddingResult(new float[]{1,0},12L,12L));
        when(embeddings.toPgVector(any())).thenReturn("[1,0]");
        when(store.stage(target,chunk,"[1,0]",12L,12L)).thenReturn(true);
        when(store.activate(target)).thenReturn(true);
        service.processBatch();
        var order = inOrder(index,store,embeddings);
        order.verify(index).reindex();
        order.verify(store).sourceSynced(target);
        order.verify(embeddings).embedForSignatureWithUsage("title\n\ncontent","semantic");
        order.verify(store).stage(target,chunk,"[1,0]",12L,12L);
        order.verify(store).activate(target);
        order.verify(index).clearSearchCache();
    }
    @Test void sourceSyncFailureDoesNotGenerateOrActivateNewProvider() {
        var target = new KnowledgeRebuildStore.Work(2,"semantic",512,"halfvec",UUID.randomUUID(),"SEMANTIC",false);
        when(store.claim("new")).thenReturn(target);
        when(index.reindex()).thenThrow(new IllegalStateException("source unavailable"));
        service.processBatch();
        verifyNoInteractions(embeddings);
        verify(store).fail(eq(target),any(KnowledgeJobFailure.class));
        verify(store,never()).activate(any());
    }
    @Test void explicitRetryChecksTheTargetModeRatherThanTheStillActiveProvider() {
        when(store.status(2)).thenReturn(java.util.Map.of("embedding_mode","SEMANTIC"));
        when(embeddings.signatureForMode("SEMANTIC")).thenReturn("semantic-target");
        service.retry(2);
        verify(store).retry(2,"semantic-target:halfvec-v1");
    }
    @Test void failedProviderRetainsProgressAndNeverActivates() {
        var chunk=new KnowledgeRebuildStore.Chunk(7,"title","content","hash");
        when(store.pending(work,5)).thenReturn(List.of(chunk));
        when(embeddings.embedForSignatureWithUsage(anyString(),eq("new"))).thenThrow(new IllegalStateException("unavailable"));
        service.processBatch();
        verify(store).fail(eq(work),any(KnowledgeJobFailure.class));
        verify(store,never()).activate(any());
    }
    @Test void lostLeaseStopsStagingAndActivation() {
        var chunk=new KnowledgeRebuildStore.Chunk(7,"title","content","hash");
        when(store.pending(work,5)).thenReturn(List.of(chunk));
        when(embeddings.embedForSignatureWithUsage(anyString(),eq("new"))).thenReturn(new KnowledgeEmbeddingService.EmbeddingResult(new float[]{1,0},12L,12L));
        when(embeddings.toPgVector(any())).thenReturn("[1,0]");
        when(store.stage(work,chunk,"[1,0]",12L,12L)).thenReturn(false);
        service.processBatch();
        verify(store,never()).activate(any());
    }
    @Test void competingInitializerRetriesWithoutTreatingContentionAsAFailedRebuild() {
        properties.getKnowledge().setAutoIndex(true);when(vectors.storageMatchesConfiguration()).thenReturn(true);
        when(index.initialize()).thenThrow(new KnowledgeSourceSyncStore.Conflict(KnowledgeSourceSyncStore.Reason.BUSY)).thenReturn(100);
        service.processBatch();service.processBatch();service.processBatch();
        verify(index,times(2)).initialize();
    }
    @Test void changedSourceWaitsForAdministratorInsteadOfOverwritingOnEveryTick() {
        properties.getKnowledge().setAutoIndex(true);when(vectors.storageMatchesConfiguration()).thenReturn(true);
        when(index.initialize()).thenThrow(new KnowledgeSourceSyncStore.Conflict(KnowledgeSourceSyncStore.Reason.REVIEW_REQUIRED));
        service.processBatch();service.processBatch();verify(index,times(1)).initialize();
    }
    @Test void mismatchedStorageDoesNotStartAutomaticRebuildOnEveryTick() {
        properties.getKnowledge().setAutoIndex(true);
        when(vectors.storageMatchesConfiguration()).thenReturn(false);
        when(vectors.embeddingDimension()).thenReturn(2);
        when(store.claim("new")).thenReturn(null);
        service.processBatch(); service.processBatch();
        verify(store,times(1)).request("new",2,"halfvec");
        verify(index,never()).initialize();
    }
}
