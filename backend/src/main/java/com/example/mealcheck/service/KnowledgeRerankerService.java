package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeRerankerService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeRerankerService.class);
    private static final int MAX_DOCUMENT_CHARS = 2400;

    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApplicationObservability observability;
    private final RemoteCallGuard remoteCallGuard;

    public KnowledgeRerankerService(AppProperties properties,
                                    HttpClient httpClient,
                                    ObjectMapper objectMapper,
                                    ApplicationObservability observability,
                                    RemoteCallGuard remoteCallGuard) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.observability = observability;
        this.remoteCallGuard = remoteCallGuard;
    }

    public List<KnowledgeSnippet> rerank(String query, List<KnowledgeSnippet> candidates, int limit) {
        int safeLimit = Math.max(1, limit);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!isConfigured()) {
            return withRoughRanks(candidates).stream().limit(safeLimit).toList();
        }

        long startedAt = observability.start();
        try {
            List<String> documents = candidates.stream().map(this::documentText).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getKnowledge().getRerankModel());
            body.put("query", query == null ? "" : query);
            body.put("documents", documents);
            body.put("top_n", documents.size());
            body.put("return_documents", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getKnowledge().getRerankUrl()))
                    .timeout(Duration.ofSeconds(safeTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getKnowledge().getRerankApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = remoteCallGuard.execute(
                    "reranker", () -> send(request));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("rerank HTTP " + response.statusCode());
            }

            List<KnowledgeSnippet> result = mergeScores(candidates, response.body(), safeLimit);
            observability.recordRerank(startedAt, providerName(), "success", candidates.size());
            return result;
        } catch (Exception e) {
            observability.recordRerank(startedAt, providerName(), "fallback", candidates.size());
            log.warn("Knowledge rerank failed; keeping hybrid retrieval order. provider={}, reason={}",
                    providerName(), e.getMessage());
            return withRoughRanks(candidates).stream().limit(safeLimit).toList();
        }
    }

    public boolean isConfigured() {
        AppProperties.Knowledge knowledge = properties.getKnowledge();
        return knowledge.isRerankEnabled()
                && hasText(knowledge.getRerankUrl())
                && hasText(knowledge.getRerankApiKey())
                && hasText(knowledge.getRerankModel());
    }

    public String providerName() {
        return hasText(properties.getKnowledge().getRerankModel())
                ? properties.getKnowledge().getRerankModel().trim()
                : "disabled";
    }

    public String cacheSignature() {
        if (!isConfigured()) {
            return "rerank:disabled";
        }
        return String.format(java.util.Locale.ROOT, "rerank:wrrf-v1:%s:%.4f:%d",
                providerName(), modelWeight(), rrfK());
    }

    private List<KnowledgeSnippet> mergeScores(List<KnowledgeSnippet> candidates,
                                               String responseBody,
                                               int limit) throws Exception {
        JsonNode results = objectMapper.readTree(responseBody).path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("rerank response missing results");
        }

        Map<Integer, Double> modelScores = new HashMap<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index >= 0 && index < candidates.size() && result.has("relevance_score")) {
                double rawScore = result.path("relevance_score").asDouble(Double.NaN);
                if (Double.isFinite(rawScore)) {
                    modelScores.put(index, rawScore);
                }
            }
        }
        if (modelScores.isEmpty()) {
            throw new IllegalStateException("rerank response contains no valid scores");
        }

        Map<Integer, Integer> rerankerRanks = rerankerRanks(candidates.size(), modelScores);
        double modelWeight = modelWeight();
        int rrfK = rrfK();
        double normalizationFactor = rrfK + 1.0;

        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> {
                    KnowledgeSnippet candidate = candidates.get(index);
                    int roughRank = candidate.getRoughRank() > 0 ? candidate.getRoughRank() : index + 1;
                    int rerankerRank = rerankerRanks.getOrDefault(index, candidates.size());
                    double combined = normalizationFactor * (
                            modelWeight / (rrfK + rerankerRank)
                                    + (1.0 - modelWeight) / (rrfK + roughRank));
                    KnowledgeSnippet scored = copyWithScores(candidate, combined,
                            roughRank, modelScores.get(index), rerankerRank, true);
                    return new ScoredSnippet(scored, combined, index);
                })
                .sorted(Comparator.comparingDouble(ScoredSnippet::score).reversed()
                        .thenComparing(scored -> scored.snippet().getRerankerRawScore(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparingInt(ScoredSnippet::originalIndex))
                .limit(limit)
                .map(ScoredSnippet::snippet)
                .toList();
    }

    private Map<Integer, Integer> rerankerRanks(int candidateCount, Map<Integer, Double> modelScores) {
        List<Integer> ordered = java.util.stream.IntStream.range(0, candidateCount).boxed()
                .sorted(Comparator
                        .comparing((Integer index) -> modelScores.get(index),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparingInt(Integer::intValue))
                .toList();
        Map<Integer, Integer> ranks = new HashMap<>();
        for (int rank = 0; rank < ordered.size(); rank++) {
            ranks.put(ordered.get(rank), rank + 1);
        }
        return ranks;
    }

    private List<KnowledgeSnippet> withRoughRanks(List<KnowledgeSnippet> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> {
                    KnowledgeSnippet candidate = candidates.get(index);
                    int roughRank = candidate.getRoughRank() > 0 ? candidate.getRoughRank() : index + 1;
                    return copyWithScores(candidate, roughScore(candidate), roughRank, null, null, false);
                })
                .toList();
    }

    private KnowledgeSnippet copyWithScores(KnowledgeSnippet source,
                                            double finalScore,
                                            int roughRank,
                                            Double rerankerRawScore,
                                            Integer rerankerRank,
                                            boolean reranked) {
        KnowledgeSnippet result = new KnowledgeSnippet(
                source.getId(), source.getTitle(), source.getContent(), source.getCategory(), finalScore);
        result.setRoughScore(roughScore(source));
        result.setRoughRank(roughRank);
        result.setRerankerRawScore(rerankerRawScore);
        result.setRerankerRank(rerankerRank);
        result.setReranked(reranked);
        return result;
    }

    private double roughScore(KnowledgeSnippet snippet) {
        return snippet.getRoughRank() > 0 || snippet.getRoughScore() != 0.0
                ? snippet.getRoughScore()
                : snippet.getScore();
    }

    private String documentText(KnowledgeSnippet snippet) {
        String text = (snippet.getTitle() == null ? "" : snippet.getTitle().trim())
                + "\n" + (snippet.getContent() == null ? "" : snippet.getContent().trim());
        return text.length() <= MAX_DOCUMENT_CHARS ? text : text.substring(0, MAX_DOCUMENT_CHARS);
    }

    private double modelWeight() {
        return Math.max(0.0, Math.min(1.0, properties.getKnowledge().getRerankModelWeight()));
    }

    private int rrfK() {
        return Math.max(1, Math.min(1000, properties.getKnowledge().getRrfK()));
    }

    private int safeTimeoutSeconds() {
        return Math.max(1, Math.min(60, properties.getKnowledge().getRerankTimeoutSeconds()));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429) {
                throw new RemoteCallGuard.NonRetryableRemoteCallException(
                        "Rerank request rejected with HTTP " + response.statusCode());
            }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new IllegalStateException("Rerank request failed with HTTP " + response.statusCode());
            }
            return response;
        } catch (RemoteCallGuard.NonRetryableRemoteCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rerank request was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Rerank request failed: " + e.getMessage(), e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ScoredSnippet(KnowledgeSnippet snippet, double score, int originalIndex) {}
}
