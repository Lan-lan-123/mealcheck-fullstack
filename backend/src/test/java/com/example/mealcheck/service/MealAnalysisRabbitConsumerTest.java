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

    private Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
