package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexServiceTest {

    @Test
    void searchRecordsEvaluationForFreshResults() {
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        RagEvaluationService ragEvaluationService = mock(RagEvaluationService.class);
        MarkdownKnowledgeChunker chunker = mock(MarkdownKnowledgeChunker.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        KnowledgeRerankerService rerankerService = mock(KnowledgeRerankerService.class);
        AppProperties properties = new AppProperties();
        KnowledgeIndexService service = new KnowledgeIndexService(
                vectorService,
                properties,
                redisCacheService,
                ragEvaluationService,
                chunker,
                observability,
                rerankerService,
                new KnowledgeResultPostProcessor(properties)
        );
        List<KnowledgeSnippet> snippets = List.of(new KnowledgeSnippet(1L, "protein", "content", "general", 0.81));

        when(redisCacheService.getJson(anyString(), any(TypeReference.class))).thenReturn(Optional.empty());
        when(redisCacheService.singleFlight(anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(vectorService.search("protein", 20)).thenReturn(snippets);
        when(rerankerService.rerank("protein", snippets, 20)).thenReturn(snippets);

        List<KnowledgeSnippet> result = service.search("protein", 5);

        assertThat(result).isEqualTo(snippets);
        verify(vectorService).search("protein", 20);
        verify(redisCacheService, org.mockito.Mockito.times(2))
                .setJsonWithJitter(anyString(), eq(snippets), any(), any());
        verify(ragEvaluationService).recordSearch(eq("protein"), eq(snippets),
                any(KnowledgeResultPostProcessor.Result.class));
    }

    @Test
    void searchRecordsEvaluationForCachedResults() {
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        RagEvaluationService ragEvaluationService = mock(RagEvaluationService.class);
        MarkdownKnowledgeChunker chunker = mock(MarkdownKnowledgeChunker.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        KnowledgeRerankerService rerankerService = mock(KnowledgeRerankerService.class);
        AppProperties properties = new AppProperties();
        KnowledgeIndexService service = new KnowledgeIndexService(
                vectorService,
                properties,
                redisCacheService,
                ragEvaluationService,
                chunker,
                observability,
                rerankerService,
                new KnowledgeResultPostProcessor(properties)
        );
        List<KnowledgeSnippet> snippets = List.of(new KnowledgeSnippet(2L, "vegetable", "content", "vegetable", 0.74));

        when(redisCacheService.getJson(anyString(), any(TypeReference.class))).thenReturn(Optional.of(snippets));

        List<KnowledgeSnippet> result = service.search("vegetable", 3);

        assertThat(result).isEqualTo(snippets);
        verify(ragEvaluationService).recordSearch("vegetable", snippets);
    }

    @Test
    void emptySearchUsesShortNegativeAndStaleCacheWindows() {
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        RagEvaluationService ragEvaluationService = mock(RagEvaluationService.class);
        MarkdownKnowledgeChunker chunker = mock(MarkdownKnowledgeChunker.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        KnowledgeRerankerService rerankerService = mock(KnowledgeRerankerService.class);
        AppProperties properties = new AppProperties();
        KnowledgeIndexService service = new KnowledgeIndexService(
                vectorService, properties, redisCacheService, ragEvaluationService, chunker,
                observability, rerankerService, new KnowledgeResultPostProcessor(properties));

        when(redisCacheService.getJson(anyString(), any(TypeReference.class))).thenReturn(Optional.empty());
        when(redisCacheService.singleFlight(anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(vectorService.search("unknown", 20)).thenReturn(List.of());
        when(rerankerService.rerank("unknown", List.of(), 20)).thenReturn(List.of());

        assertThat(service.search("unknown", 5)).isEmpty();

        org.mockito.ArgumentCaptor<java.time.Duration> ttl =
                org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        verify(redisCacheService, org.mockito.Mockito.times(2))
                .setJsonWithJitter(anyString(), eq(List.of()), ttl.capture(), any());
        assertThat(ttl.getAllValues()).containsExactlyInAnyOrder(
                java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5));
    }
}
