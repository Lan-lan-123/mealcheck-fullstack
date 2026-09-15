package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class KnowledgeEmbeddingService {
    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HashEmbeddingService fallback;
    private final ApplicationObservability observability;
    private final RemoteCallGuard remoteCallGuard;

    public KnowledgeEmbeddingService(AppProperties properties,
                                     HttpClient httpClient,
                                     ObjectMapper objectMapper,
                                     HashEmbeddingService fallback,
                                     ApplicationObservability observability,
                                     RemoteCallGuard remoteCallGuard) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.observability = observability;
        this.remoteCallGuard = remoteCallGuard;
    }

    public float[] embed(String text) {
        long startedAt = observability.start();
        String provider = isSemanticConfigured() ? "semantic" : "hash";
        try {
            if (text == null || text.isBlank()) {
                float[] empty = new float[Math.max(1, properties.getKnowledge().getEmbeddingDim())];
                observability.recordEmbedding(startedAt, provider, "success");
                return empty;
            }
            if (!isSemanticConfigured()) {
                float[] embedding = fallback.embed(text);
                observability.recordEmbedding(startedAt, provider, "success");
                return embedding;
            }
            AppProperties.Knowledge knowledge = properties.getKnowledge();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", knowledge.getEmbeddingModel());
            body.put("input", text == null ? "" : text);
            body.put("dimensions", knowledge.getEmbeddingDim());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(AiEndpointResolver.embeddings(knowledge.getEmbeddingBaseUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, knowledge.getEmbeddingTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + knowledge.getEmbeddingApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = remoteCallGuard.execute(
                    "embedding", () -> send(request));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Embedding request failed with HTTP " + response.statusCode());
            }

            JsonNode embedding = objectMapper.readTree(response.body())
                    .path("data").path(0).path("embedding");
            if (!embedding.isArray() || embedding.size() != knowledge.getEmbeddingDim()) {
                throw new IllegalStateException("Embedding response dimension does not match mealcheck.knowledge.embedding-dim");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            observability.recordEmbedding(startedAt, provider, "success");
            return vector;
        } catch (Exception e) {
            observability.recordEmbedding(startedAt, provider, "failure");
            throw new IllegalStateException("Semantic embedding failed: " + e.getMessage(), e);
        }
    }

    public String toPgVector(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) value.append(',');
            value.append(String.format(Locale.US, "%.6f", vector[i]));
        }
        return value.append(']').toString();
    }

    public boolean isSemanticConfigured() {
        AppProperties.Knowledge knowledge = properties.getKnowledge();
        return notBlank(knowledge.getEmbeddingApiKey())
                && notBlank(knowledge.getEmbeddingBaseUrl())
                && notBlank(knowledge.getEmbeddingModel());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429) {
                throw new RemoteCallGuard.NonRetryableRemoteCallException(
                        "Embedding request rejected with HTTP " + response.statusCode());
            }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new IllegalStateException("Embedding request failed with HTTP " + response.statusCode());
            }
            return response;
        } catch (RemoteCallGuard.NonRetryableRemoteCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding request was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding request failed: " + e.getMessage(), e);
        }
    }

    public String providerName() {
        return isSemanticConfigured()
                ? "SemanticEmbedding / " + properties.getKnowledge().getEmbeddingModel()
                : "HashEmbeddingService / Development fallback";
    }

    public String signature() {
        AppProperties.Knowledge knowledge = properties.getKnowledge();
        if (!isSemanticConfigured()) {
            return "hash-v1:" + knowledge.getEmbeddingDim();
        }
        String provider = knowledge.getEmbeddingBaseUrl().trim() + "|" + knowledge.getEmbeddingModel().trim();
        return "semantic-v1:" + knowledge.getEmbeddingModel().trim() + ":"
                + knowledge.getEmbeddingDim() + ":" + sha256(provider).substring(0, 16);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build embedding signature", e);
        }
    }
}
