package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.dto.StructureEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.HttpResponse;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AiErrorPrivacyTest {
    private static final String PRIVATE = "PRIVATE_MODEL_PAYLOAD sk-test-secret";

    @Test void malformedAndErrorResponsesDoNotEnterExceptionsOrSharedStatus() throws Exception {
        var properties = new AppProperties(); properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("https://example.invalid/v1");
        properties.getAi().setModel("test-model");
        var status = new AiStatusService(); var transport = mock(AiHttpTransport.class);
        var client = new AiChatClient(properties, new ObjectMapper(), status, mock(ApplicationObservability.class), transport);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(transport.send(anyString(), anyString(), any(), anyString())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        for (String body : List.of(PRIVATE, "{\"private\":\"" + PRIVATE + "\"}")) {
            when(response.body()).thenReturn(body);
            assertThatThrownBy(() -> client.complete("vision-recognition", List.of(), 0.1))
                    .isInstanceOf(AiChatClient.AiClientException.class).hasMessageNotContaining(PRIVATE);
        }
        when(response.statusCode()).thenReturn(400); when(response.body()).thenReturn(PRIVATE);
        assertThatThrownBy(() -> client.complete("vision-recognition", List.of(), 0.1)).hasMessageNotContaining(PRIVATE);
        when(transport.send(anyString(), anyString(), any(), anyString())).thenThrow(new IllegalStateException(PRIVATE));
        assertThatThrownBy(() -> client.complete("vision-recognition", List.of(), 0.1)).hasMessageNotContaining(PRIVATE);
        status.recordFailure("another-operation", PRIVATE);
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(status.recentCalls())).doesNotContain(PRIVATE);
    }

    @Test void fallbackAdviceDoesNotPersistExceptionDetails() {
        var ai = mock(AiChatClient.class); when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(anyString(), anyList(), anyDouble())).thenThrow(new AiChatClient.AiClientException(PRIVATE));
        var recognition = new RecognitionResult(); recognition.setFoods(List.of());
        var evaluation = new StructureEvaluation(); evaluation.setRiskTags(List.of()); evaluation.setSummary("summary");
        String advice = new AdviceGenerationService(ai).generate(recognition, evaluation, List.of(), "balanced");
        assertThat(advice).contains("本地规则建议").doesNotContain(PRIVATE);
    }
}
