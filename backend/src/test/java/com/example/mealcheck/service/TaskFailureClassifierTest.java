package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class TaskFailureClassifierTest {
    @Test void uploadSaturationHasItsOwnRecoverableCategory() {
        var failure = TaskFailureClassifier.classify(new UploadBusyException(22), "VALIDATE");
        assertThat(failure.code()).isEqualTo("UPLOAD_BUSY");
        assertThat(failure.recoverable()).isTrue();
    }
    @Test void classifiesWrappedFailuresWithoutPersistingSecretsOrProviderBodies() {
        var failure=TaskFailureClassifier.classify(new IllegalStateException("secret-key",new DataIntegrityViolationException("private response")),"SAVE");
        assertThat(failure.code()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(failure.summary()).doesNotContain("secret-key","private response");
        assertThat(failure.recoverable()).isTrue();
    }
    @Test void invalidBusinessInputCannotBeBlindlyRetried() {
        assertThat(TaskFailureClassifier.classify(new ResponseStatusException(HttpStatus.BAD_REQUEST,"nonfood"),"ANALYZE").recoverable()).isFalse();
        assertThat(TaskFailureClassifier.classify(new java.net.http.HttpTimeoutException("provider body"),"ANALYZE").code()).isEqualTo("REMOTE_TIMEOUT");
        assertThat(TaskFailureClassifier.classify(new RemoteCallGuard.NonRetryableRemoteCallException("api-key"),"ANALYZE").code()).isEqualTo("REMOTE_CONFIGURATION");
    }
}
