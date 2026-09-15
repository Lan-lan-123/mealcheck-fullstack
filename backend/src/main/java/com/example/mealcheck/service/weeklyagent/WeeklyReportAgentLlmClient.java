package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.service.AiStatusService;
import com.example.mealcheck.service.ApplicationObservability;
import com.example.mealcheck.service.RemoteCallGuard;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class WeeklyReportAgentLlmClient {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportAgentLlmClient.class);

    private final AppProperties properties;
    private final AiStatusService aiStatusService;
    private final ApplicationObservability observability;
    private final RemoteCallGuard remoteCallGuard;

    public WeeklyReportAgentLlmClient(AppProperties properties,
                                      AiStatusService aiStatusService,
                                      ApplicationObservability observability,
                                      RemoteCallGuard remoteCallGuard) {
        this.properties = properties;
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

    public Optional<String> complete(String operation, String prompt, double temperature) {
        if (!isConfigured()) {
            return Optional.empty();
        }

        long startedAt = System.nanoTime();
        try {
            String answer = remoteCallGuard.execute(
                    "weekly-report-agent",
                    () -> model(temperature).chat(prompt));
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            aiStatusService.recordSuccess(operation, latencyMs);
            observability.recordAiCall(startedAt, operation, "success");
            return Optional.ofNullable(answer).filter(value -> !value.isBlank());
        } catch (Exception error) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            aiStatusService.recordFailure(operation, error.getMessage(), latencyMs);
            observability.recordAiCall(startedAt, operation, "failure");
            log.warn("Weekly report agent degraded. operation={}, reason={}", operation, error.getMessage());
            return Optional.empty();
        }
    }

    private OpenAiChatModel model(double temperature) {
        return OpenAiChatModel.builder()
                .apiKey(properties.getAi().getApiKey())
                .baseUrl(properties.getAi().getBaseUrl())
                .modelName(properties.getAi().getModel())
                .temperature(Math.max(0.0, Math.min(1.0, temperature)))
                .timeout(Duration.ofSeconds(Math.max(5,
                        Math.min(60, properties.getWeeklyReport().getAgentTimeoutSeconds()))))
                .build();
    }
}
