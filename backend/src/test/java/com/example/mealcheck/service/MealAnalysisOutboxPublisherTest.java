package com.example.mealcheck.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MealAnalysisOutboxPublisherTest {
    private final MealAnalysisOutboxStore store = mock(MealAnalysisOutboxStore.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final MealAnalysisOutboxPublisher publisher = new MealAnalysisOutboxPublisher(store, rabbit, new SimpleMeterRegistry(),new BackgroundTaskDispatcher(Runnable::run));
    private final MealAnalysisOutboxStore.Dispatch dispatch = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());

    @Test void brokerNackDoesNotMarkPublished() {
        when(store.claim()).thenReturn(dispatch);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store, never()).confirmed(any());
        verify(store, times(1)).claim();
    }

    @Test void unroutableMessageIsRetriedEvenWithPositiveConfirm() {
        when(store.claim()).thenReturn(dispatch);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "exchange", "key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store, never()).confirmed(any());
    }

    @Test void unavailableBrokerLeavesDispatchForLeaseRecovery() {
        when(store.claim()).thenReturn(dispatch);
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException()))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store, never()).confirmed(any());
    }
}
