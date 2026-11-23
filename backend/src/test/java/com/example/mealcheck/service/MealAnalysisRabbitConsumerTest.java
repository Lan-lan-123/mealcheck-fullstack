package com.example.mealcheck.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealAnalysisRabbitConsumerTest {
    private final MealAnalysisRequestStore store = mock(MealAnalysisRequestStore.class);
    private final MealAnalysisService analysis = mock(MealAnalysisService.class);
    private final MealAnalysisRabbitConsumer consumer = new MealAnalysisRabbitConsumer(store, analysis, new SimpleMeterRegistry(),
            mock(MealAnalysisLeaseService.class));

    @Test void rejectsMalformedPayloadWithoutReadingDatabaseOrCallingModel() {
        assertThatThrownBy(() -> consumer.consume(message("not-a-dispatch")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(store, analysis);
    }

    @Test void obsoleteOrActiveDeliveryDoesNotCallModel() {
        UUID id = UUID.randomUUID();
        consumer.consume(message(id.toString()));
        verify(store).takeMessage(id);
        verifyNoInteractions(analysis);
    }

    @Test void databaseFailurePropagatesRatherThanAcknowledgingUnclaimedWork() {
        UUID id = UUID.randomUUID();
        when(store.takeMessage(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> consumer.consume(message(id.toString())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        verifyNoInteractions(analysis);
    }

    @Test void obsoletePayloadReadDoesNotCallModelsFailTheNewOwnerOrSendToDeadLetter() {
        UUID id = UUID.randomUUID();
        var work = mock(MealAnalysisRequestStore.Work.class);
        when(store.takeMessage(id)).thenReturn(work);
        when(work.user()).thenReturn(mock(com.example.mealcheck.security.UserPrincipal.class));
        when(store.loadImage(work)).thenThrow(new MealAnalysisRequestStore.WorkNoLongerOwnedException());
        consumer.consume(message(id.toString()));
        verify(store).loadImage(work);
        verify(store, never()).fail(any(), any(), any(), any(), any());
        verify(analysis, never()).prepareValidated(any(), any(), any());
        verify(store, never()).complete(any(), any(), any(), any());
    }

    @Test void missingStorageForALiveTaskStillRecordsFailureAndRejectsDelivery() {
        UUID id = UUID.randomUUID();
        var user = new com.example.mealcheck.entity.UserAccount(); user.setId(1L); user.setUsername("tester");
        var work = new MealAnalysisRequestStore.Work(new com.example.mealcheck.security.UserPrincipal(user), "key", "token", "balanced", null);
        when(store.takeMessage(id)).thenReturn(work);
        var failure = new IllegalStateException("storage unavailable");
        when(store.loadImage(work)).thenThrow(failure);
        assertThatThrownBy(() -> consumer.consume(message(id.toString()))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store).fail(1L, "key", "token", "ANALYZE", failure);
        verify(analysis, never()).prepareValidated(any(), any(), any());
    }

    private Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
