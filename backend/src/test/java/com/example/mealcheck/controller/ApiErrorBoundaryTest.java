package com.example.mealcheck.controller;

import com.example.mealcheck.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorBoundaryTest {
    private final AiChatClient ai = mock(AiChatClient.class);
    private final ImageUploadValidationService validation = mock(ImageUploadValidationService.class);
    private final MockMultipartFile image = new MockMultipartFile("image", "meal.png", "image/png", new byte[]{1});
    private MockMvc http;

    @BeforeEach void setup() {
        when(ai.isConfigured()).thenReturn(true);
        when(validation.validateAndNormalize(any())).thenReturn(new ValidatedImage(new byte[]{1}, "image/png", ".png", 1, 1, "meal.png"));
        http = MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null),
                new MealController(null, null, null, null, null),
                new VisionEvaluationController(validation, new VisionFoodRecognitionService(ai, new ObjectMapper()), ai))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void malformedJsonAndWrongQueryTypesAreClientErrors() throws Exception {
        http.perform(post("/api/auth/login").contentType("application/json").content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        http.perform(get("/api/meals").param("page", "oops"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        http.perform(get("/api/meals").param("from", "not-a-date")).andExpect(status().isBadRequest());
    }

    @Test void missingMultipartPartAndOversizedUploadHaveCorrectStatuses() throws Exception {
        http.perform(multipart("/api/meals/analyze")).andExpect(status().isBadRequest());
        when(validation.validateAndNormalize(any())).thenThrow(new MaxUploadSizeExceededException(100));
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
        verify(ai, never()).complete(anyString(), anyList(), anyDouble());
    }

    @Test void oversizedUnicodePasswordsAreRejectedBeforeAuthentication() throws Exception {
        http.perform(post("/api/auth/register").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"" + "餐".repeat(25)
                        + "\",\"captchaId\":\"id\",\"captchaAnswer\":\"4\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test void realVisionServicePreservesBudgetUnavailableAndDeadlineStatuses() throws Exception {
        doThrow(new AiBudgetService.BudgetExceededException(100, 3600)).when(ai).complete(anyString(), anyList(), anyDouble());
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.code").value("AI_BUDGET_EXCEEDED"));
        doThrow(new AiBudgetService.BudgetUnavailableException()).when(ai).complete(anyString(), anyList(), anyDouble());
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_BUDGET_UNAVAILABLE"));
        doThrow(new RemoteCallGuard.RemoteCallDeadlineException("private detail")).when(ai).complete(anyString(), anyList(), anyDouble());
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("AI_DEADLINE_EXCEEDED"));
    }

    @Test void aiExceptionsNeverExposeRawMessages() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            doThrow(new AiChatClient.AiClientException("PRIVATE_MODEL_PAYLOAD sk-test-secret")).when(ai).complete(anyString(), anyList(), anyDouble());
            http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value("AI 服务调用失败，请稍后重试。"));
            doReturn("PRIVATE_MODEL_PAYLOAD sk-test-secret").when(ai).complete(anyString(), anyList(), anyDouble());
            http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value("AI 服务调用失败，请稍后重试。"));
            org.assertj.core.api.Assertions.assertThat(logs.list)
                    .allSatisfy(event -> org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                            .doesNotContain("PRIVATE_MODEL_PAYLOAD", "sk-test-secret"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
