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
        return aiChatClient.completeWith(
                "assistant-benchmark-judge",
                "assistant-benchmark-judge",
                firstNonBlank(evaluation.getJudgeApiKey(), properties.getAi().getApiKey()),
                firstNonBlank(evaluation.getJudgeBaseUrl(), properties.getAi().getBaseUrl()),
                modelName(),
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

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
