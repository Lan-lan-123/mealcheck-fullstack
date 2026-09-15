package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRerankerServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void reranksCandidatesAndBlendsModelWithInitialScores() throws Exception {
        AppProperties properties = configuredProperties();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"results":[
                  {"index":1,"relevance_score":0.95},
                  {"index":0,"relevance_score":0.10}
                ]}
                """);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        RemoteCallGuard guard = passThroughGuard();
        KnowledgeRerankerService service = new KnowledgeRerankerService(
                properties, httpClient, new ObjectMapper(), observability, guard);
        List<KnowledgeSnippet> candidates = List.of(
                new KnowledgeSnippet(1L, "通用建议", "内容", "general", 0.90),
                new KnowledgeSnippet(2L, "减脂建议", "内容", "fat_loss", 0.80)
        );

        List<KnowledgeSnippet> result = service.rerank("如何减脂", candidates, 2);

        assertThat(result).extracting(KnowledgeSnippet::getId).containsExactly(2L, 1L);
        assertThat(result.get(0).getRoughScore()).isEqualTo(0.80);
        assertThat(result.get(0).getRoughRank()).isEqualTo(2);
        assertThat(result.get(0).getRerankerRawScore()).isEqualTo(0.95);
        assertThat(result.get(0).getRerankerRank()).isEqualTo(1);
        assertThat(result.get(0).getFinalScore()).isGreaterThan(result.get(1).getFinalScore());
        assertThat(result).allMatch(KnowledgeSnippet::isReranked);
        verify(observability).recordRerank(anyLong(),
                org.mockito.ArgumentMatchers.eq("test-reranker"),
                org.mockito.ArgumentMatchers.eq("success"),
                org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void keepsHybridOrderWhenRerankerIsDisabled() {
        AppProperties properties = new AppProperties();
        HttpClient httpClient = mock(HttpClient.class);
        KnowledgeRerankerService service = new KnowledgeRerankerService(
                properties, httpClient, new ObjectMapper(), mock(ApplicationObservability.class),
                mock(RemoteCallGuard.class));
        List<KnowledgeSnippet> candidates = List.of(
                new KnowledgeSnippet(1L, "first", "content", "general", 0.9),
                new KnowledgeSnippet(2L, "second", "content", "general", 0.8)
        );

        List<KnowledgeSnippet> result = service.rerank("query", candidates, 1);

        assertThat(result).extracting(KnowledgeSnippet::getId).containsExactly(1L);
        assertThat(result.get(0).getRoughRank()).isEqualTo(1);
        assertThat(result.get(0).getRoughScore()).isEqualTo(0.9);
        assertThat(result.get(0).getRerankerRawScore()).isNull();
        assertThat(result.get(0).isReranked()).isFalse();
        verifyNoInteractions(httpClient);
    }

    private AppProperties configuredProperties() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setRerankEnabled(true);
        properties.getKnowledge().setRerankUrl("https://example.test/v1/rerank");
        properties.getKnowledge().setRerankApiKey("test-key");
        properties.getKnowledge().setRerankModel("test-reranker");
        properties.getKnowledge().setRerankModelWeight(0.8);
        return properties;
    }

    private RemoteCallGuard passThroughGuard() {
        RemoteCallGuard guard = mock(RemoteCallGuard.class);
        when(guard.execute(any(), any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(1).get());
        return guard;
    }
}
