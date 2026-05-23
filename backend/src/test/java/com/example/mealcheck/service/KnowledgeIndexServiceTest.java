package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
        KnowledgeIndexService service = new KnowledgeIndexService(
                vectorService,
                new AppProperties(),
                redisCacheService,
                ragEvaluationService
        );
        List<KnowledgeSnippet> snippets = List.of(new KnowledgeSnippet(1L, "protein", "content", "general", 0.81));

        when(redisCacheService.getJson(anyString(), any(TypeReference.class))).thenReturn(Optional.empty());
        when(vectorService.search("protein", 5)).thenReturn(snippets);

        List<KnowledgeSnippet> result = service.search("protein", 5);

        assertThat(result).isEqualTo(snippets);
        verify(vectorService).search("protein", 5);
        verify(redisCacheService).setJson(anyString(), eq(snippets), any());
        verify(ragEvaluationService).recordSearch("protein", snippets);
    }

    @Test
    void searchRecordsEvaluationForCachedResults() {
        PgVectorKnowledgeService vectorService = mock(PgVectorKnowledgeService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        RagEvaluationService ragEvaluationService = mock(RagEvaluationService.class);
        KnowledgeIndexService service = new KnowledgeIndexService(
                vectorService,
                new AppProperties(),
                redisCacheService,
                ragEvaluationService
        );
        List<KnowledgeSnippet> snippets = List.of(new KnowledgeSnippet(2L, "vegetable", "content", "vegetable", 0.74));

        when(redisCacheService.getJson(anyString(), any(TypeReference.class))).thenReturn(Optional.of(snippets));

        List<KnowledgeSnippet> result = service.search("vegetable", 3);

        assertThat(result).isEqualTo(snippets);
        verify(ragEvaluationService).recordSearch("vegetable", snippets);
    }
}
