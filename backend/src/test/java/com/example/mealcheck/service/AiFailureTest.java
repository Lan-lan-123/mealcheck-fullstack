package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiFailureTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void quotaRequiresARecognizedCodeAnd403AloneMeansAccessDenied() {
        assertThat(AiFailure.rejected(403,"{\"code\":\"Arrearage\"}",json).code()).isEqualTo("MODEL_QUOTA_EXHAUSTED");
        assertThat(AiFailure.rejected(429,"{\"error\":{\"code\":\"insufficient_quota\"}}",json).code()).isEqualTo("MODEL_QUOTA_EXHAUSTED");
        assertThat(AiFailure.rejected(403,"{\"message\":\"quota sk-private\"}",json).code()).isEqualTo("MODEL_ACCESS_DENIED");
        assertThat(AiFailure.rejected(401,"invalid body sk-private",json).getMessage()).doesNotContain("sk-private");
    }
    @Test void wrappedReviewFailuresRemainServiceFailuresInsteadOfKnowledgeGaps() throws Exception {
        var provider=AiFailure.rejected(403,"{\"error\":{\"code\":\"Arrearage\"}}",json);
        var output=AssistantGroundingGuard.parse(AssistantGroundingGuard.unavailable(new IllegalStateException("wrapper",provider),null));
        assertThat(output.path("failureCode").asText()).isEqualTo("MODEL_QUOTA_EXHAUSTED");
        assertThat(output.path("answerStatus").asText()).isEqualTo("DEGRADED");
        assertThat(output.path("summary").asText()).contains("额度").doesNotContain("知识库证据不足");
        assertThat(output.path("referenceIds")).isEmpty();
        assertThat(AiFailure.classify(new IllegalStateException(new java.net.http.HttpTimeoutException("hidden"))).code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(AiFailure.classify(new IllegalStateException(new java.io.IOException("hidden"))).code()).isEqualTo("MODEL_CONNECTION_FAILED");
    }
}
