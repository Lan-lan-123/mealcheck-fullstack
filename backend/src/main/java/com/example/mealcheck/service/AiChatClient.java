package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
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
    private final ObjectMapper objectMapper;
    private final AiStatusService aiStatusService;
    private final ApplicationObservability observability;
    private final AiHttpTransport transport;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai.input-cost-per-million:0}") private double inputPrice;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai.output-cost-per-million:0}") private double outputPrice;
    private final ThreadLocal<Usage> usageForCall=new ThreadLocal<>();
    public record Usage(Long inputTokens,Long outputTokens,Double estimatedCost) { }
    public Usage lastUsage() { return usageForCall.get(); }
    public void clearUsage() { usageForCall.remove(); }

    public AiChatClient(AppProperties properties,
                        ObjectMapper objectMapper,
                        AiStatusService aiStatusService,
                        ApplicationObservability observability,
                        AiHttpTransport transport) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.aiStatusService = aiStatusService;
        this.observability = observability;
        this.transport = transport;
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

        return completeWith(
                operation,
                "ai-chat",
                properties.getAi().getApiKey(),
                properties.getAi().getBaseUrl(),
                properties.getAi().getModel(),
                properties.getAi().getTimeoutSeconds(),
                messages,
                temperature
        );
    }

    public String completeWith(String operation,
                               String guardName,
                               String apiKey,
                               String baseUrl,
                               String model,
                               int timeoutSeconds,
                               List<Map<String, Object>> messages,
                               double temperature) {
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()
                || model == null || model.isBlank()) {
            throw new IllegalStateException("AI API Key, Base URL or model is not configured.");
        }

        long startedAt = System.nanoTime();
        clearUsage();
        try {
            URI endpoint = AiEndpointResolver.chatCompletions(baseUrl);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("messages", messages);

            log.info("Calling AI chat completion. operation={}", operation);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = transport.send(operation,guardName,request,objectMapper.writeValueAsString(body));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiClientException("AI 服务调用失败，请稍后重试。");
            }

            String content = extractMessageContent(response.body());
            var usage=objectMapper.readTree(response.body()).path("usage");
            long input=usage.path("prompt_tokens").asLong(0),output=usage.path("completion_tokens").asLong(0);
            boolean known=usage.has("prompt_tokens") && usage.has("completion_tokens");
            boolean priced=inputPrice>0 && outputPrice>0 && model.equals(properties.getAi().getModel()) && baseUrl.equals(properties.getAi().getBaseUrl());
            double cost=known && priced ? (input*inputPrice+output*outputPrice)/1_000_000.0 : 0;
            usageForCall.set(new Usage(known?input:null,known?output:null,known && priced?cost:null));
            aiStatusService.recordSuccess(operation, elapsedMs(startedAt));
            observability.recordAiCall(startedAt, operation, "success");
            return content;
        } catch (AiBudgetService.BudgetExceededException | AiBudgetService.BudgetUnavailableException | RemoteCallGuard.RemoteCallDeadlineException e) {
            throw e;
        } catch (AiClientException e) {
            aiStatusService.recordFailure(operation, e.getMessage(), elapsedMs(startedAt));
            observability.recordAiCall(startedAt, operation, "failure");
            throw e;
        } catch (Exception e) {
            aiStatusService.recordFailure(operation, e.getMessage(), elapsedMs(startedAt));
            observability.recordAiCall(startedAt, operation, "failure");
            throw new AiClientException("AI 服务调用失败，请稍后重试。", e);
        }
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String extractMessageContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new AiClientException("AI 服务返回结果无效，请稍后重试。");
        }
        return content;
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
