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
    @Test void blankQueriesDoNotGenerateVectorsOrPopulateCaches() {
        var vector = mock(PgVectorKnowledgeService.class);
        var redis = mock(RedisCacheService.class);
        var properties = new AppProperties();
        var index = new KnowledgeIndexService(vector,properties,redis,mock(RagEvaluationService.class),
                new MarkdownKnowledgeChunker(properties),mock(ApplicationObservability.class),mock(KnowledgeRerankerService.class),new KnowledgeResultPostProcessor(properties));
        for (String query : new String[]{null,""," \n\t"}) assertThat(index.search(query,5)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(vector,redis);
    }
    @Test void publishedSplitterTakesPrecedenceOverEnvironmentAfterRestart() {
        var properties = new AppProperties();
        var vector = mock(PgVectorKnowledgeService.class);
        var index = new KnowledgeIndexService(vector,properties,mock(RedisCacheService.class),mock(RagEvaluationService.class),
                new MarkdownKnowledgeChunker(properties),mock(ApplicationObservability.class),mock(KnowledgeRerankerService.class),new KnowledgeResultPostProcessor(properties));
        when(vector.splitterStrategy("diet_guides.md")).thenReturn("langchain4j-recursive");
        assertThat(index.activeSplitterStrategy()).isEqualTo("langchain4j-recursive");
        when(vector.splitterStrategy("diet_guides.md")).thenReturn(null);
        assertThat(index.activeSplitterStrategy()).isEqualTo("recursive-markdown");
    }

    @Test
    void lateOldSearchCannotRepopulateTheNewRevisionAcrossInstances() throws Exception {
        var vector = mock(PgVectorKnowledgeService.class);
        var redis = mock(RedisCacheService.class);
        var evaluation = mock(RagEvaluationService.class);
        var reranker = mock(KnowledgeRerankerService.class);
        var properties = new AppProperties();
        var first = new KnowledgeIndexService(vector, properties, redis, evaluation,
                mock(MarkdownKnowledgeChunker.class), mock(ApplicationObservability.class), reranker,
                new KnowledgeResultPostProcessor(properties));
        var second = new KnowledgeIndexService(vector, properties, redis, evaluation,
                mock(MarkdownKnowledgeChunker.class), mock(ApplicationObservability.class), reranker,
                new KnowledgeResultPostProcessor(properties));
        var revision = new java.util.concurrent.atomic.AtomicLong(1);
        var cache = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        when(vector.searchRevision()).thenAnswer(call -> revision.get());
        when(redis.getJson(anyString(), any(TypeReference.class)))
                .thenAnswer(call -> Optional.ofNullable(cache.get(call.getArgument(0))));
        when(redis.singleFlight(anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        org.mockito.Mockito.doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(redis).setJsonWithJitter(anyString(), any(), any(), any());
        var old = List.of(new KnowledgeSnippet(1L, "old", "旧内容", "general", .9));
        var updated = List.of(new KnowledgeSnippet(1L, "new", "新内容", "general", .9));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(vector.search("protein", 20)).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return old;
            }
            return updated;
        });
        when(reranker.rerank(eq("protein"), any(), eq(20))).thenAnswer(call -> call.getArgument(1));
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> first.search("protein", 5));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            revision.incrementAndGet(); // Simulate a committed knowledge edit on another instance.
            assertThat(second.search("protein", 5)).isEqualTo(updated);
            release.countDown();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(old);
            assertThat(second.search("protein", 5)).isEqualTo(updated);
            assertThat(cache.keySet()).anyMatch(key -> key.startsWith("rag:search:complete-v2:v1:"))
                    .anyMatch(key -> key.startsWith("rag:search:complete-v2:v2:"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

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
