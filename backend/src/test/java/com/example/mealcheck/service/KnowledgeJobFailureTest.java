package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class KnowledgeJobFailureTest {
    @Test void distinguishesProviderFailuresWithoutPersistingResponseBodies() {
        var auth=KnowledgeJobFailure.classify(new IllegalStateException("wrapper",
                new RemoteCallGuard.NonRetryableRemoteCallException("secret-key provider body",401)));
        assertThat(auth.code()).isEqualTo("EMBEDDING_AUTH");
        assertThat(auth.retryable()).isFalse();
        assertThat(auth.summary()).doesNotContain("secret-key","provider body");
        var rate=KnowledgeJobFailure.classify(new RemoteCallGuard.RetryableRemoteCallException(429,Duration.ZERO));
        assertThat(rate.code()).isEqualTo("EMBEDDING_RATE_LIMIT");
        assertThat(rate.retryable()).isTrue();
        assertThat(KnowledgeJobFailure.http(503).code()).isEqualTo("EMBEDDING_PROVIDER");
        assertThat(KnowledgeJobFailure.http(400).retryable()).isFalse();
    }
    @Test void distinguishesTimeoutNetworkDatabaseAndInvalidPayload() throws Exception {
        assertThat(KnowledgeJobFailure.classify(new java.net.http.HttpTimeoutException("key")).code()).isEqualTo("EMBEDDING_TIMEOUT");
        assertThat(KnowledgeJobFailure.classify(new java.net.ConnectException("key")).code()).isEqualTo("EMBEDDING_NETWORK");
        assertThat(KnowledgeJobFailure.classify(new org.springframework.dao.DataAccessResourceFailureException("key")).code()).isEqualTo("DATABASE_ERROR");
        try {new com.fasterxml.jackson.databind.ObjectMapper().readTree("invalid json");fail("Expected malformed payload");}
        catch(com.fasterxml.jackson.core.JsonProcessingException error) {
            assertThat(KnowledgeJobFailure.classify(error).code()).isEqualTo("EMBEDDING_RESPONSE");
            assertThat(KnowledgeJobFailure.classify(error).retryable()).isFalse();
        }
        var dimension=new KnowledgeJobFailure("EMBEDDING_DIMENSION","维度不匹配",false);
        assertThat(KnowledgeJobFailure.classify(new IllegalStateException(new KnowledgeJobFailure.EmbeddingException(dimension)))).isEqualTo(dimension);
    }
}
