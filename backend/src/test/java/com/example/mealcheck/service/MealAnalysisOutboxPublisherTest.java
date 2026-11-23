package com.example.mealcheck.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MealAnalysisOutboxPublisherTest {
    private final MealAnalysisOutboxStore store = mock(MealAnalysisOutboxStore.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final MealAnalysisOutboxPublisher publisher = new MealAnalysisOutboxPublisher(store, rabbit, new SimpleMeterRegistry(),new BackgroundTaskDispatcher(Runnable::run));
    private final MealAnalysisOutboxStore.Dispatch dispatch = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());

    @Test void brokerNackDoesNotMarkPublished() {
        when(store.claimBatch(anyInt())).thenReturn(List.of(dispatch));
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store, never()).confirmed(any());
        verify(store, times(1)).claimBatch(8);
    }

    @Test void unroutableMessageIsRetriedEvenWithPositiveConfirm() {
        when(store.claimBatch(anyInt())).thenReturn(List.of(dispatch));
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
        when(store.claimBatch(anyInt())).thenReturn(List.of(dispatch));
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException()))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store, never()).confirmed(any());
    }

    @Test void wholeWindowIsSentBeforeAnyConfirmAndPartialFailureDoesNotLoseOtherAcks() {
        var second = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());
        var third = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());
        when(store.claimBatch(8)).thenReturn(List.of(dispatch, second, third));
        var correlations = new ArrayList<CorrelationData>();
        doAnswer(call -> {
            correlations.add(call.getArgument(3));
            assertThat(correlations).hasSizeLessThanOrEqualTo(3);
            verify(store, never()).confirmed(any());
            if (correlations.size() == 3) {
                // Deliberately confirm out of order, including a NACK in the middle.
                correlations.get(2).getFuture().complete(new CorrelationData.Confirm(true, null));
                correlations.get(1).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
                correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store).confirmed(dispatch);
        verify(store, never()).confirmed(second);
        verify(store).confirmed(third);
        verify(store, times(1)).claimBatch(8);
    }

    @Test void missingAckHasOneWindowDeadlineAndLateAckCannotWriteBack() {
        ReflectionTestUtils.setField(publisher, "confirmTimeoutMs", 100L);
        var second = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());
        when(store.claimBatch(8)).thenReturn(List.of(dispatch, second));
        var correlations = new ArrayList<CorrelationData>();
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlations.add(correlation);
            if (correlations.size() == 2) correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store, never()).confirmed(dispatch);
        verify(store).confirmed(second);
        correlations.get(0).getFuture().complete(new CorrelationData.Confirm(true, null));
        verify(store, never()).confirmed(dispatch);
        verify(store, times(1)).claimBatch(8);
    }

    @Test void sendFailureStillCommitsAlreadySentAcksAndLeavesUnsentRowsRecoverable() {
        var second = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());
        var third = new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID());
        when(store.claimBatch(8)).thenReturn(List.of(dispatch, second, third));
        doAnswer(call -> {
            Message message = call.getArgument(2);
            if (message.getMessageProperties().getMessageId().equals(second.eventId().toString())) throw new org.springframework.amqp.AmqpException("unavailable");
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        verify(store).confirmed(dispatch);
        verify(store, never()).confirmed(second);
        verify(store, never()).confirmed(third);
        verify(rabbit, times(2)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test void windowCapacityAndTotalRunAreBounded() {
        ReflectionTestUtils.setField(publisher, "windowSize", 3);
        var limits = new ArrayList<Integer>();
        when(store.claimBatch(anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(0);
            limits.add(limit);
            return java.util.stream.IntStream.range(0, limit)
                    .mapToObj(i -> new MealAnalysisOutboxStore.Dispatch(UUID.randomUUID(), UUID.randomUUID())).toList();
        });
        var inWindow = new ArrayList<CorrelationData>();
        doAnswer(call -> {
            inWindow.add(call.getArgument(3));
            assertThat(inWindow).hasSizeLessThanOrEqualTo(limits.get(limits.size() - 1));
            if (inWindow.size() == limits.get(limits.size() - 1)) {
                for (var correlation : inWindow) correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                inWindow.clear();
            }
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish();
        assertThat(limits).containsExactly(3, 3, 3, 3, 3, 3, 2);
        verify(store, times(20)).confirmed(any());
    }

    @Test void overlappingCallsCannotOpenAnotherWindow() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.claimBatch(8)).thenReturn(List.of(dispatch), List.of());
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(publisher::publish);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            publisher.publish();
            verify(store, times(1)).claimBatch(8);
            release.countDown();
            running.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
