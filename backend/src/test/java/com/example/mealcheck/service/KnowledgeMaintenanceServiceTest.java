package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeMaintenanceServiceTest {
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
    @Test void failedProviderRetainsProgressAndNeverActivates() {
        var chunk=new KnowledgeRebuildStore.Chunk(7,"title","content","hash");
        when(store.pending(work,5)).thenReturn(List.of(chunk));
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("unavailable"));
        service.processBatch();
        verify(store).release(work,true);
        verify(store,never()).activate(any());
    }
    @Test void lostLeaseStopsStagingAndActivation() {
        var chunk=new KnowledgeRebuildStore.Chunk(7,"title","content","hash");
        when(store.pending(work,5)).thenReturn(List.of(chunk));
        when(embeddings.embed(anyString())).thenReturn(new float[]{1,0});
        when(embeddings.toPgVector(any())).thenReturn("[1,0]");
        when(store.stage(work,chunk,"[1,0]")).thenReturn(false);
        service.processBatch();
        verify(store,never()).activate(any());
    }
    @Test void mismatchedStorageDoesNotStartAutomaticRebuildOnEveryTick() {
        properties.getKnowledge().setAutoIndex(true);
        when(vectors.storageMatchesConfiguration()).thenReturn(false);
        when(vectors.embeddingDimension()).thenReturn(2);
        when(store.claim("new")).thenReturn(null);
        service.processBatch(); service.processBatch();
        verify(store,times(1)).request("new",2,"halfvec");
        verify(index,never()).reindex();
    }
}
