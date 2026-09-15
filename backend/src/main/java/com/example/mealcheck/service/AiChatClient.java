package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiChatClient {
    private static final Logger log = LoggerFactory.getLogger(AiChatClient.class);

    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiStatusService aiStatusService;
    private final ApplicationObservability observability;
    private final RemoteCallGuard remoteCallGuard;

    public AiChatClient(AppProperties properties,
                        HttpClient httpClient,
                        ObjectMapper objectMapper,
                        AiStatusService aiStatusService,
                        ApplicationObservability observability,
                        RemoteCallGuard remoteCallGuard) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.aiStatusService = aiStatusService;
        this.observability = observability;
        this.remoteCallGuard = remoteCallGuard;
    }

    public boolean isConfigured() {
        return properties.getAi().getApiKey() != null
                && !properties.getAi().getApiKey().isBlank()
                && properties.getAi().getBaseUrl() != null
                && !properties.getAi().getBaseUrl().isBlank()
                && properties.getAi().getModel() != null
                && !properties.getAi().getModel().isBlank();
    }

    public String complete(String operation, List<Map<String, Object>> messages, double temperature) {
        if (!isConfigured()) {
            throw new IllegalStateException("AI API Key, Base URL or model is not configured.");
        }

        long startedAt = System.nanoTime();
        try {
            URI endpoint = AiEndpointResolver.chatCompletions(properties.getAi().getBaseUrl());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getAi().getModel());
            body.put("temperature", temperature);
            body.put("messages", messages);

            log.info("Calling AI chat completion. operation={}, endpoint={}, model={}",
                    operation, endpoint, properties.getAi().getModel());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = remoteCallGuard.execute(
                    "ai-chat", () -> send(request, "AI chat request"));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiClientException("AI request failed: HTTP " + response.statusCode()
                        + " - " + truncate(response.body(), 500));
            }

            String content = extractMessageContent(response.body());
            aiStatusService.recordSuccess(operation, elapsedMs(startedAt));
            observability.recordAiCall(startedAt, operation, "success");
            return content;
        } catch (AiClientException e) {
            aiStatusService.recordFailure(operation, e.getMessage(), elapsedMs(startedAt));
            observability.recordAiCall(startedAt, operation, "failure");
            throw e;
        } catch (Exception e) {
            aiStatusService.recordFailure(operation, e.getMessage(), elapsedMs(startedAt));
            observability.recordAiCall(startedAt, operation, "failure");
            throw new AiClientException("AI request failed: " + e.getMessage(), e);
        }
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429) {
                throw new RemoteCallGuard.NonRetryableRemoteCallException(
                        operation + " rejected with HTTP " + response.statusCode());
            }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new AiClientException(operation + " failed with HTTP " + response.statusCode());
            }
            return response;
        } catch (RemoteCallGuard.NonRetryableRemoteCallException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiClientException(operation + " was interrupted", e);
        } catch (Exception e) {
            throw new AiClientException(operation + " failed: " + e.getMessage(), e);
        }
    }

    private String extractMessageContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new AiClientException("AI response missing choices[0].message.content: "
                    + truncate(responseBody, 500));
        }
        return content;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    public static class AiClientException extends RuntimeException {
        public AiClientException(String message) {
            super(message);
        }

        public AiClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
