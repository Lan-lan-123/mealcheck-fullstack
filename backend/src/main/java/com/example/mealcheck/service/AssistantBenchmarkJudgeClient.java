package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AssistantBenchmarkJudgeClient {
    private final AiChatClient aiChatClient;
    private final AppProperties properties;

    public AssistantBenchmarkJudgeClient(AiChatClient aiChatClient, AppProperties properties) {
        this.aiChatClient = aiChatClient;
        this.properties = properties;
    }

    public String complete(List<Map<String, Object>> messages, double temperature) {
        AppProperties.AssistantEvaluation evaluation = properties.getAssistantEvaluation();
        var connection=properties.getAi().connection();
        requireJudgeCredential(evaluation, connection);
        return aiChatClient.completeWith(
                "assistant-benchmark-judge",
                "assistant-benchmark-judge",
                firstNonBlank(evaluation.getJudgeApiKey(), connection.apiKey()),
                firstNonBlank(evaluation.getJudgeBaseUrl(), connection.baseUrl()),
                firstNonBlank(evaluation.getJudgeModel(), connection.model()),
                Math.max(1, evaluation.getJudgeTimeoutSeconds()),
                messages,
                temperature
        );
    }

    public String modelName() {
        return firstNonBlank(
                properties.getAssistantEvaluation().getJudgeModel(),
                properties.getAi().getModel()
        );
    }

    public boolean isConfigured() {
        AppProperties.AssistantEvaluation evaluation = properties.getAssistantEvaluation();
        if (!canReuseCredential(evaluation, properties.getAi().connection())) return false;
        return !firstNonBlank(evaluation.getJudgeApiKey(), properties.getAi().getApiKey()).isBlank()
                && !firstNonBlank(evaluation.getJudgeBaseUrl(), properties.getAi().getBaseUrl()).isBlank()
                && !modelName().isBlank();
    }

    public boolean isIndependent() {
        String configuredJudge = properties.getAssistantEvaluation().getJudgeModel();
        return configuredJudge != null
                && !configuredJudge.isBlank()
                && !configuredJudge.equalsIgnoreCase(properties.getAi().getModel());
    }

    private void requireJudgeCredential(AppProperties.AssistantEvaluation evaluation,
                                        AppProperties.Ai.Connection connection) {
        if (!canReuseCredential(evaluation, connection)) {
            throw new IllegalStateException("独立裁判使用不同服务地址时，必须配置裁判 API Key");
        }
    }

    private boolean canReuseCredential(AppProperties.AssistantEvaluation evaluation,
                                       AppProperties.Ai.Connection connection) {
        if (evaluation.getJudgeApiKey() != null && !evaluation.getJudgeApiKey().isBlank()) return true;
        String base = evaluation.getJudgeBaseUrl();
        return base == null || base.isBlank()
                || base.trim().replaceAll("/+$", "").equals(
                        firstNonBlank(connection.baseUrl(), "").replaceAll("/+$", ""));
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
