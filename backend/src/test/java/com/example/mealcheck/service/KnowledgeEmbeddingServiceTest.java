package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeEmbeddingServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void usesConfiguredSemanticEmbeddingEndpoint() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setEmbeddingApiKey("test-key");
        properties.getKnowledge().setEmbeddingBaseUrl("https://example.test/v1");
        properties.getKnowledge().setEmbeddingModel("semantic-model");
        properties.getKnowledge().setEmbeddingDim(3);

        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        AiHttpTransport transport = new AiHttpTransport(client, new RemoteCallGuard(properties), mock(AiBudgetService.class), properties, new ObjectMapper(), mock(ApplicationObservability.class), mock(AiCallConcurrency.class));

        KnowledgeEmbeddingService service = new KnowledgeEmbeddingService(
                properties, new ObjectMapper(), new HashEmbeddingService(properties),
                mock(ApplicationObservability.class), transport);

        assertThat(service.embed("蔬菜和蛋白质")).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(service.providerName()).contains("semantic-model");
        assertThat(service.signature()).startsWith("semantic-v1:semantic-model:3:");
    }

    @Test
    void usesHashFallbackWhenSemanticEndpointIsNotConfigured() {
        AppProperties properties = new AppProperties();
        HashEmbeddingService fallback = new HashEmbeddingService(properties);
        KnowledgeEmbeddingService service = new KnowledgeEmbeddingService(
                properties, new ObjectMapper(), fallback,
                mock(ApplicationObservability.class), mock(AiHttpTransport.class));

        assertThat(service.embed("rice")).hasSize(512).containsExactly(fallback.embed("rice"));
        assertThat(service.providerName()).contains("fallback");
        assertThat(service.signature()).isEqualTo("hash-v1:512");
    }

}
