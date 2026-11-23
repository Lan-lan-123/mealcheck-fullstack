package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiChatClientTest {
    @Test void wrappedProviderFailuresKeepTheirSpecificTaskCategory() {
        var properties=new AppProperties();properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("https://example.test/v1");properties.getAi().setModel("test-model");
        var transport=mock(AiHttpTransport.class);
        var client=new AiChatClient(properties,new ObjectMapper(),new AiStatusService(),mock(ApplicationObservability.class),transport);
        var failures=List.of(
                new IllegalStateException("transport wrapper",new java.net.http.HttpTimeoutException("private-provider-payload")),
                new RemoteCallGuard.RetryableRemoteCallException(429,null),
                new RemoteCallGuard.RetryableRemoteCallException(503,null),
                new RemoteCallGuard.NonRetryableRemoteCallException("private-api-key",401),
                new RemoteCallGuard.NonRetryableRemoteCallException("private-api-key",402));
        var codes=List.of("REMOTE_TIMEOUT","MODEL_REQUEST_LIMITED","MODEL_SERVICE_ERROR","REMOTE_CONFIGURATION","MODEL_REQUEST_LIMITED");
        for(int i=0;i<failures.size();i++) {
            when(transport.send(anyString(),anyString(),any(HttpRequest.class),anyString())).thenThrow(failures.get(i));
            final String expected=codes.get(i);
            assertThatThrownBy(()->client.complete("vision-recognition",List.of(),.1)).satisfies(error->{
                var classified=TaskFailureClassifier.classify(error,"ANALYZE");
                assertThat(classified.code()).isEqualTo(expected);assertThat(classified.recoverable()).isTrue();
                assertThat(classified.summary()).doesNotContain("private-provider-payload","private-api-key");
            });
        }
    }
    @Test @SuppressWarnings("unchecked") void malformedEnvelopesAreNotMisreportedAsBadUserImages() {
        var properties=new AppProperties();properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("https://example.test/v1");properties.getAi().setModel("test-model");
        var transport=mock(AiHttpTransport.class);var response=mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(transport.send(anyString(),anyString(),any(HttpRequest.class),anyString())).thenReturn(response);
        var client=new AiChatClient(properties,new ObjectMapper(),new AiStatusService(),mock(ApplicationObservability.class),transport);
        for(String body:List.of("not-json","null","[]","{\"choices\":[]}")) {
            when(response.body()).thenReturn(body);
            assertThatThrownBy(()->client.complete("vision-recognition",List.of(),.1)).isInstanceOfSatisfying(AiChatClient.InvalidResponseException.class,
                    error->assertThat(TaskFailureClassifier.classify(error,"ANALYZE").code()).isEqualTo("MODEL_RESPONSE_INVALID"));
        }
    }
    @Test void visionUsesDedicatedModelWhileTextUsesSelectedConnection() throws Exception {
        var properties = new AppProperties();
        properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("https://example.test/v1");
        properties.getAi().setModel("qwen3.8-2.4t-a95b");
        properties.getAi().setVisionModel("qwen3.7-flash");
        var mapper = new ObjectMapper();
        var transport = mock(AiHttpTransport.class);
        java.net.http.HttpResponse<String> response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        when(transport.send(anyString(),eq("ai-chat"),any(HttpRequest.class),anyString())).thenAnswer(call -> {
            String expected = "vision-recognition".equals(call.getArgument(0)) ? "qwen3.7-flash" : "qwen3.8-2.4t-a95b";
            assertThat(mapper.readTree(call.getArgument(3, String.class)).path("model").asText()).isEqualTo(expected);
            return response;
        });
        var client = new AiChatClient(properties,mapper,new AiStatusService(),mock(ApplicationObservability.class),transport);
        client.complete("vision-recognition",List.of(),.2);
        assertThat(client.lastModel()).isEqualTo("qwen3.7-flash");
        client.complete("meal-advice",List.of(),.2);
        assertThat(client.lastModel()).isEqualTo("qwen3.8-2.4t-a95b");
    }
    @Test void timeoutIsVisibleInStatusAndMetricsWithoutChangingItsFailureType() {
        var properties = new AppProperties();
        properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("http://127.0.0.1:1/v1");
        properties.getAi().setModel("test-model");
        var status = new AiStatusService();
        var metrics = mock(ApplicationObservability.class);
        var transport = mock(AiHttpTransport.class);
        var deadline = new RemoteCallGuard.RemoteCallDeadlineException("AI operation deadline exceeded");
        when(transport.send(eq("vision-recognition"),eq("ai-chat"),any(HttpRequest.class),anyString()))
                .thenThrow(deadline);
        var client = new AiChatClient(properties,new ObjectMapper(),status,metrics,transport);
        assertThatThrownBy(() -> client.complete("vision-recognition", List.of(Map.of("role","user","content","test")), .2))
                .isSameAs(deadline);
        assertThat(status.failureCount()).isEqualTo(1);
        assertThat(status.successCount()).isZero();
        verify(metrics).recordAiCall(anyLong(),eq("vision-recognition"),eq("failure"));
        assertThat(client.lastUsage()).isNull();
    }

    @Test void budgetRejectionDoesNotMasqueradeAsProviderFailure() {
        var properties = new AppProperties();
        properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("http://127.0.0.1:1/v1");
        properties.getAi().setModel("test-model");
        var status = new AiStatusService();
        var metrics = mock(ApplicationObservability.class);
        var transport = mock(AiHttpTransport.class);
        when(transport.send(anyString(),anyString(),any(HttpRequest.class),anyString()))
                .thenThrow(new AiBudgetService.BudgetExceededException(10,60));
        var client = new AiChatClient(properties,new ObjectMapper(),status,metrics,transport);
        assertThatThrownBy(() -> client.complete("vision-recognition",List.of(),.2))
                .isInstanceOf(AiBudgetService.BudgetExceededException.class);
        assertThat(status.failureCount()).isZero();
        verifyNoInteractions(metrics);
    }
}
