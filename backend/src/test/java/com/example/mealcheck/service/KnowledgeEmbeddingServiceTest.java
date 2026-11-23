package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeEmbeddingServiceTest {
    @Test void explicitLocalModeDoesNotReadOrDecryptAnUnavailableSemanticProfile() {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var profiles = mock(KnowledgeEmbeddingProfileStore.class);
        when(profiles.active()).thenThrow(new IllegalStateException("Cannot decrypt obsolete profile"));
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        service.setProfiles(profiles);
        assertThat(service.embedForMode("午餐","LOCAL")).hasSize(512);
        assertThat(service.embedForSignature("午餐","hash-v1:512:halfvec-v1")).hasSize(512);
        verifyNoInteractions(profiles,transport);
    }
    @Test @SuppressWarnings("unchecked")
    void environmentCanUseBgeM3WithoutSendingUnsupportedDimensions() throws Exception {
        var properties = new AppProperties();
        properties.getKnowledge().setEmbeddingModel("BAAI/bge-m3");
        properties.getKnowledge().setEmbeddingDim(1024);
        properties.getKnowledge().setEmbeddingSendDimensions(false);
        properties.getKnowledge().setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        properties.getKnowledge().setEmbeddingApiKey("fixture-key");
        var transport = mock(AiHttpTransport.class);
        var response = mock(HttpResponse.class);
        float[] vector = new float[1024]; vector[0] = 1;
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":" + new ObjectMapper().writeValueAsString(vector) + "}]}");
        when(transport.send(any(), any(), any())).thenAnswer(call -> {
            var body = new ObjectMapper().readTree(call.getArgument(2, String.class));
            assertThat(body.path("model").asText()).isEqualTo("BAAI/bge-m3");
            assertThat(body.has("dimensions")).isFalse();
            return response;
        });
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        assertThat(service.embed("午餐搭配")).hasSize(1024);
    }

    @Test @SuppressWarnings("unchecked")
    void activeDatabaseProfileOverridesLegacyEnvironmentDimensionAndRequestOptions() throws Exception {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        var profiles = mock(KnowledgeEmbeddingProfileStore.class);
        var profile = new KnowledgeEmbeddingProfileStore.Profile("BAAI/bge-m3","https://api.siliconflow.cn/v1","fixture-key",1024,false);
        when(profiles.active()).thenReturn(new KnowledgeEmbeddingProfileStore.Stored(2,profile,"ADMIN",null));
        service.setProfiles(profiles);
        var settings = mock(KnowledgeEmbeddingSettingsStore.class);
        when(settings.mode()).thenReturn("SEMANTIC"); service.setSettings(settings);
        var response = mock(HttpResponse.class);
        float[] vector = new float[1024]; vector[0] = 1;
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":" + new ObjectMapper().writeValueAsString(vector) + "}]}");
        when(transport.send(any(),any(),any())).thenAnswer(call -> {
            var body = new ObjectMapper().readTree(call.getArgument(2,String.class));
            assertThat(body.has("dimensions")).isFalse();
            return response;
        });
        assertThat(properties.getKnowledge().getEmbeddingDim()).isEqualTo(512);
        assertThat(service.activeDimension()).isEqualTo(1024);
        assertThat(service.embedForSignature("午餐",service.signature()+":halfvec-v1")).hasSize(1024);
    }

    @Test @SuppressWarnings("unchecked")
    void rejectsUnusableProviderVectorsWithoutLeakingResponseFragments() throws Exception {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(transport.send(any(),any(),any())).thenReturn(response);
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        var profile = new KnowledgeEmbeddingProfileStore.Profile("fixture","https://provider.test/v1","private-key",2,false);
        for (String body : new String[]{"{\"data\":[{\"embedding\":[0,0]}]}",
                "{\"data\":[{\"embedding\":[1e-50,0]}]}", "{\"data\":[{\"embedding\":[1e100,0]}]}",
                "{\"data\":[{\"embedding\":[\"private-key\",0]}]}", "private-key invalid JSON"}) {
            when(response.body()).thenReturn(body);
            assertThatThrownBy(() -> service.embedWithProfile("正文",profile))
                    .isInstanceOf(KnowledgeJobFailure.EmbeddingException.class)
                    .hasMessageNotContaining("private-key").hasNoCause();
        }
    }

    @Test void vectorSerializationPreservesSmallComponentsAndRejectsNonfiniteValues() throws Exception {
        var properties = new AppProperties();
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),mock(AiHttpTransport.class));
        float[] vector = {0.00000001f, 0.123456789f, -0.00000001f};
        var parsed = new ObjectMapper().readTree(service.toPgVector(vector));
        for (int i=0;i<vector.length;i++) assertThat(parsed.get(i).floatValue()).isEqualTo(vector[i]);
        assertThatThrownBy(() -> service.toPgVector(new float[]{Float.NaN})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.toPgVector(new float[]{Float.POSITIVE_INFINITY})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @SuppressWarnings("unchecked")
    void normalizesProviderVectorsSoHalfPrecisionCannotOverflowOrLoseEveryComponent() throws Exception {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(transport.send(any(),any(),any())).thenReturn(response);
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        var profile = new KnowledgeEmbeddingProfileStore.Profile("fixture","https://provider.test/v1","fixture-key",2,false);
        for (String components : new String[]{"1e-20,1e-20", "1e30,1e30"}) {
            when(response.body()).thenReturn("{\"data\":[{\"embedding\":["+components+"]}]}");
            float[] vector=service.embedWithProfile("正文",profile);
            assertThat(vector[0]).isCloseTo(0.70710677f,org.assertj.core.data.Offset.offset(0.000001f));
            assertThat(vector[1]).isEqualTo(vector[0]);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void returnsUsageFromTheSameEmbeddingCallAndKeepsMissingOrInvalidFieldsUnknown() throws Exception {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(transport.send(any(),any(),any())).thenReturn(response);
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        var profile = new KnowledgeEmbeddingProfileStore.Profile("fixture","https://provider.test/v1","key",2,false);
        String[] usages = {"{\"prompt_tokens\":22,\"total_tokens\":22}", "{\"total_tokens\":17}", "{}",
                "{\"prompt_tokens\":0,\"total_tokens\":0}", "{\"prompt_tokens\":-1,\"total_tokens\":\"19\"}",
                "{\"input_tokens\":12,\"total_tokens\":12}", "{\"prompt_tokens\":1.5,\"total_tokens\":9223372036854775808}"};
        Long[] inputs = {22L,null,null,0L,null,12L,null};
        Long[] totals = {22L,17L,null,0L,null,12L,null};
        for (int i=0;i<usages.length;i++) {
            when(response.body()).thenReturn("{\"data\":[{\"embedding\":[1,0]}],\"usage\":"+usages[i]+"}");
            var result = service.embedWithProfileWithUsage("标题\n\n正文",profile);
            assertThat(result.vector()).containsExactly(1,0);
            assertThat(result.inputTokens()).isEqualTo(inputs[i]);
            assertThat(result.totalTokens()).isEqualTo(totals[i]);
        }
        org.mockito.Mockito.verify(transport,org.mockito.Mockito.times(usages.length)).send(any(),any(),any());
    }

    @Test void localAndEmptyInputsDoNotInventProviderUsageOrMakeRemoteCalls() {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var service = new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        for (String text : new String[]{"知识正文", ""}) {
            var result = service.embedForModeWithUsage(text,"LOCAL");
            assertThat(result.vector()).hasSize(512);
            assertThat(result.inputTokens()).isNull();
            assertThat(result.totalTokens()).isNull();
        }
        verifyNoInteractions(transport);
    }

    @Test @SuppressWarnings("unchecked")
    void explicitProfileUsesItsOwnCredentialsAndSupportsFixedDimensionProviders() throws Exception {
        var properties=new AppProperties();
        var transport=mock(AiHttpTransport.class);
        var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        when(transport.send(any(),any(),any())).thenAnswer(call -> {
            HttpRequest request=call.getArgument(1);
            assertThat(request.uri().toString()).isEqualTo("https://pinned.test/v1/embeddings");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer pinned-key");
            var body=new ObjectMapper().readTree(call.getArgument(2,String.class));
            assertThat(body.path("model").asText()).isEqualTo("pinned-model");
            assertThat(body.has("dimensions")).isFalse();
            return response;
        });
        var service=new KnowledgeEmbeddingService(properties,new ObjectMapper(),new HashEmbeddingService(properties),mock(ApplicationObservability.class),transport);
        var pinned=new KnowledgeEmbeddingProfileStore.Profile("pinned-model","https://pinned.test/v1","pinned-key",3,false);
        assertThat(service.embedWithProfile("知识",pinned)).hasSize(3);
        assertThat(service.activeMode()).isEqualTo("LOCAL");
        var invalid=new KnowledgeEmbeddingProfileStore.Profile("pinned-model","https://pinned.test/v1","pinned-key",2,false);
        assertThatThrownBy(()->service.embedWithProfile("知识",invalid)).hasMessageContaining("维度");
    }

    @Test
    void selectedLocalModeDoesNotUseConfiguredRemoteModel() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setEmbeddingApiKey("dedicated-key");
        properties.getKnowledge().setEmbeddingBaseUrl("https://example.test/v1");
        properties.getKnowledge().setEmbeddingModel(KnowledgeEmbeddingService.QWEN_FLASH);
        var transport = mock(AiHttpTransport.class);
        var fallback = new HashEmbeddingService(properties);
        var service = new KnowledgeEmbeddingService(properties, new ObjectMapper(), fallback,
                mock(ApplicationObservability.class), transport);
        var settings = mock(KnowledgeEmbeddingSettingsStore.class);
        when(settings.mode()).thenReturn("LOCAL");
        service.setSettings(settings);
        assertThat(service.embed("乳糖不耐受")).containsExactly(fallback.embed("乳糖不耐受"));
        assertThat(service.signature()).isEqualTo("hash-v1:512");
        verifyNoInteractions(transport);
    }

    @Test
    @SuppressWarnings("unchecked")
    void qwenFlashUsesAliyunCredentialsAndExplicitModeWithoutChangingActiveMode() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAi().setApiKey("aliyun-chat-key");
        properties.getAi().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        var transport = mock(AiHttpTransport.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        float[] vector = new float[512]; vector[0] = 1;
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":" +
                new ObjectMapper().writeValueAsString(vector) + "}]}");
        when(transport.send(any(), any(), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(1);
            assertThat(request.uri().toString()).endsWith("/compatible-mode/v1/embeddings");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer aliyun-chat-key");
            var body = new ObjectMapper().readTree(call.getArgument(2, String.class));
            assertThat(body.path("model").asText()).isEqualTo(KnowledgeEmbeddingService.QWEN_FLASH);
            assertThat(body.path("dimensions").asInt()).isEqualTo(512);
            return response;
        });
        var service = new KnowledgeEmbeddingService(properties, new ObjectMapper(), new HashEmbeddingService(properties),
                mock(ApplicationObservability.class), transport);
        assertThat(service.activeMode()).isEqualTo("LOCAL");
        assertThat(service.isSemanticAvailable()).isTrue();
        assertThat(service.embedForMode("牛奶替代", "SEMANTIC")).hasSize(512);
        assertThat(service.activeMode()).isEqualTo("LOCAL");
        assertThat(service.signatureForMode("SEMANTIC")).startsWith("semantic-v1:qwen3.7-text-embedding-flash:512:");
    }

    @Test
    void missingOrUnrelatedCredentialsCannotSilentlyFallBackOrBeForwarded() {
        AppProperties properties = new AppProperties();
        properties.getAi().setApiKey("other-provider-key");
        properties.getAi().setBaseUrl("https://other-provider.test/v1");
        var transport = mock(AiHttpTransport.class);
        var service = new KnowledgeEmbeddingService(properties, new ObjectMapper(), new HashEmbeddingService(properties),
                mock(ApplicationObservability.class), transport);
        assertThat(service.isSemanticAvailable()).isFalse();
        assertThatThrownBy(() -> service.embedForMode("知识", "SEMANTIC"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("API Key");
        properties.getAi().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getKnowledge().setEmbeddingBaseUrl("https://another.test/v1");
        assertThat(service.isSemanticAvailable()).isFalse();
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsSignatureFromAnotherProviderBeforeGeneratingVectors() {
        var properties = new AppProperties();
        var transport = mock(AiHttpTransport.class);
        var service = new KnowledgeEmbeddingService(properties, new ObjectMapper(), new HashEmbeddingService(properties),
                mock(ApplicationObservability.class), transport);
        assertThatThrownBy(() -> service.embedForSignature("知识", "hash-v1:256:halfvec-v1"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(transport);
    }

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

        assertThat(service.embed("蔬菜和蛋白质")).containsExactly(0.26726124f, 0.5345225f, 0.80178374f);
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
