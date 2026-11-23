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
    @Test void unclearVisionResponseReturnsStableClientError() throws Exception {
        when(ai.complete(anyString(), anyList(), anyDouble())).thenReturn("{\"foodImage\":false,\"imageQuality\":\"UNCLEAR\",\"foods\":[]}");
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_UNCLEAR"));
    }
    private final AiChatClient ai = mock(AiChatClient.class);
    private final ImageUploadValidationService validation = mock(ImageUploadValidationService.class);
    private final MockMultipartFile image = new MockMultipartFile("image", "meal.png", "image/png", new byte[]{1});
    private MockMvc http;

    private static ValidatedImage validImage(java.awt.Color color) throws Exception {
        var pixels=new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=pixels.createGraphics();graphics.setColor(color);graphics.fillRect(0,0,32,24);graphics.dispose();
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",bytes);
        return new ValidatedImage(bytes.toByteArray(),"image/png",".png",32,24,"meal.png");
    }
    @Test void localBlackImageReturnsUnclearWithoutCallingAi() throws Exception {
        when(validation.validateAndNormalize(any())).thenReturn(validImage(java.awt.Color.BLACK));
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_UNCLEAR"));
        verify(ai,never()).complete(anyString(),anyList(),anyDouble());
    }
    @BeforeEach void setup() throws Exception {
        when(ai.isConfigured()).thenReturn(true);
        when(validation.validateAndNormalize(any())).thenReturn(validImage(java.awt.Color.ORANGE));
        http = MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null, null, null),
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
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MODEL_RESPONSE_INVALID"))
                    .andExpect(jsonPath("$.message").value("模型返回结果格式无效，请稍后使用原图重试"));
            org.assertj.core.api.Assertions.assertThat(logs.list)
                    .allSatisfy(event -> org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                            .doesNotContain("PRIVATE_MODEL_PAYLOAD", "sk-test-secret"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test void typedProviderFailuresReachTheHttpBoundaryWithoutLeakingPayloads() throws Exception {
        var causes=java.util.List.of(new java.net.http.HttpTimeoutException("PRIVATE_BODY"),
                new RemoteCallGuard.RetryableRemoteCallException(429,null),
                new RemoteCallGuard.NonRetryableRemoteCallException("sk-private",401));
        var codes=java.util.List.of("REMOTE_TIMEOUT","MODEL_REQUEST_LIMITED","REMOTE_CONFIGURATION");
        var statuses=java.util.List.of(504,429,502);
        for(int i=0;i<causes.size();i++) {
            doThrow(new AiChatClient.AiClientException("PRIVATE_BODY",causes.get(i))).when(ai).complete(anyString(),anyList(),anyDouble());
            http.perform(multipart("/api/admin/vision-evaluation/recognize").file(image))
                    .andExpect(status().is(statuses.get(i))).andExpect(jsonPath("$.code").value(codes.get(i)))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_BODY"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sk-private"))));
        }
    }
}
