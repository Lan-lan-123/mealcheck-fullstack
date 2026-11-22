package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeJobLifecycleServiceTest {
    final AppProperties properties=new AppProperties();
    final KnowledgeRebuildStore store=mock(KnowledgeRebuildStore.class);
    final KnowledgeEmbeddingService embeddings=mock(KnowledgeEmbeddingService.class);
    final VectorCompressionService compression=new VectorCompressionService(properties);
    final KnowledgeJobLifecycleService lifecycle=new KnowledgeJobLifecycleService(store,embeddings,compression,properties);
    final KnowledgeRebuildPreviewService previews=mock(KnowledgeRebuildPreviewService.class);
    KnowledgeJobLifecycleServiceTest() {
        lifecycle.setPreviews(previews);
        when(embeddings.dimensionForMode(anyString())).thenReturn(512);
        when(embeddings.signatureForMode("LOCAL")).thenReturn("hash-v1:512");
        when(embeddings.signatureForMode("SEMANTIC")).thenReturn("semantic-v1:fixture:512:abc");
        when(embeddings.activeMode()).thenReturn("LOCAL");
    }
    @Test void unavailableProviderInvalidatesOnlyIncompatibleTasks() {
        lifecycle.reconcile();
        verify(store).invalidateIncompatible("hash-v1:512:halfvec-v1",null,"hash-v1:512:halfvec-v1");
    }
    @Test void resubmissionCapturesTheNewSplitterButRetryKeepsTheOriginalJob() {
        when(store.status(9)).thenReturn(Map.of("status","FAILED","embedding_mode","LOCAL","signature","hash-v1:512:halfvec-v1",
                "scope","ALL","splitter_strategy","langchain4j-recursive"));
        lifecycle.retry(9);
        verify(store).retry(9,"hash-v1:512:halfvec-v1");
        lifecycle.resubmit(9,"LOCAL","structural-markdown","reviewed","admin");
        verify(previews).submit("LOCAL",null,"structural-markdown","reviewed","admin");
        assertThatThrownBy(() -> lifecycle.resubmit(9,"LOCAL","unsupported"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void changedConfigurationRequiresResubmissionInsteadOfRetryingOldVectors() {
        when(store.status(7)).thenReturn(Map.of("status","FAILED","embedding_mode","LOCAL","signature","hash-v1:256:halfvec-v1","scope","ALL"));
        assertThatThrownBy(()->lifecycle.retry(7)).hasMessageContaining("重新提交");
        verify(store,never()).retry(anyLong(),anyString());
        assertThatThrownBy(() -> lifecycle.resubmit(7,"LOCAL")).hasMessageContaining("预览");
        lifecycle.resubmit(7,"LOCAL",null,"reviewed","admin");
        verify(previews).submit("LOCAL",null,null,"reviewed","admin");
        verify(store,never()).requestBuild(anyString(),anyInt(),anyString(),anyString());
    }
    @Test void matchingFailureCanResumeButSelectedResubmitCannotChangeProvider() {
        when(store.status(8)).thenReturn(Map.of("status","FAILED","embedding_mode","LOCAL","signature","hash-v1:512:halfvec-v1","scope","SELECTED"));
        when(store.targets(8)).thenReturn(List.of(11L,13L));
        lifecycle.retry(8);
        verify(store).retry(8,"hash-v1:512:halfvec-v1");
        lifecycle.resubmit(8,"LOCAL",null,"reviewed","admin");
        verify(previews).submit("LOCAL",List.of(11L,13L),null,"reviewed","admin");
        verify(store,never()).requestSelected(anyString(),anyInt(),anyString(),anyString(),anyList());
        when(embeddings.isSemanticAvailable()).thenReturn(true);
        assertThatThrownBy(()->lifecycle.resubmit(8,"SEMANTIC")).hasMessageContaining("整库重建");
    }

    @Test void staleResubmissionIsRejectedByTheSameGuardAsANewBuild() {
        when(store.status(9)).thenReturn(Map.of("status","CANCELLED","scope","ALL"));
        when(previews.submit("LOCAL",null,null,"stale","admin"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请重新预览"));
        assertThatThrownBy(() -> lifecycle.resubmit(9,"LOCAL",null,"stale","admin")).hasMessageContaining("预览");
        verify(store,never()).requestBuild(anyString(),anyInt(),anyString(),anyString());
    }
}
