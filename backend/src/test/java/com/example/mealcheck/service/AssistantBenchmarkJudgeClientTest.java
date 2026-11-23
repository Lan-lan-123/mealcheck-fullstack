package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantBenchmarkJudgeClientTest {
    private final AppProperties properties = new AppProperties();
    private final AiChatClient chat = mock(AiChatClient.class);
    private final AssistantBenchmarkJudgeClient judge = new AssistantBenchmarkJudgeClient(chat, properties);

    private void generation() {
        properties.getAi().setApiKey("generation-key");
        properties.getAi().setBaseUrl("https://api.siliconflow.cn/v1");
        properties.getAi().setModel("Qwen/Qwen3.8-27B");
    }

    @Test void dedicatedJudgeUsesItsOwnCredentialWithoutChangingGeneration() {
        generation();
        var config = properties.getAssistantEvaluation();
        config.setJudgeApiKey("judge-key");
        config.setJudgeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setJudgeModel("kimi-k3");
        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", "score"));
        when(chat.completeWith("assistant-benchmark-judge", "assistant-benchmark-judge", "judge-key",
                config.getJudgeBaseUrl(), "kimi-k3", 60, messages, 0)).thenReturn("{}");
        assertThat(judge.complete(messages, 0)).isEqualTo("{}");
        assertThat(judge.isConfigured()).isTrue();
        assertThat(judge.isIndependent()).isTrue();
        assertThat(properties.getAi().getModel()).isEqualTo("Qwen/Qwen3.8-27B");
        assertThat(properties.getAi().getApiKey()).isEqualTo("generation-key");
    }

    @Test void missingCrossProviderCredentialFailsBeforeAnyCall() {
        generation();
        properties.getAssistantEvaluation().setJudgeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getAssistantEvaluation().setJudgeModel("kimi-k3");
        assertThat(judge.isConfigured()).isFalse();
        assertThatThrownBy(() -> judge.complete(List.of(), 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("裁判 API Key");
        verifyNoInteractions(chat);
    }

    @Test void legacySameProviderFallbackRemainsAvailable() {
        generation();
        assertThat(judge.isConfigured()).isTrue();
        assertThat(judge.isIndependent()).isFalse();
        properties.getAssistantEvaluation().setJudgeBaseUrl("https://api.siliconflow.cn/v1/");
        properties.getAssistantEvaluation().setJudgeModel("another-model");
        assertThat(judge.isConfigured()).isTrue();
        assertThat(judge.isIndependent()).isTrue();
    }
}
