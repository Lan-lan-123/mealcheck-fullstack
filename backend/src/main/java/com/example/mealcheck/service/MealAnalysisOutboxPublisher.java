package com.example.mealcheck.service;

import com.example.mealcheck.config.MealAnalysisRabbitConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(name = "mealcheck.analysis.dispatch", havingValue = "rabbitmq", matchIfMissing = true)
public class MealAnalysisOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(MealAnalysisOutboxPublisher.class);
    private final MealAnalysisOutboxStore store;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;
    private final BackgroundTaskDispatcher background;
    private final AtomicBoolean publishing = new AtomicBoolean();
    @Value("${mealcheck.analysis.outbox-window-size:8}")
    private int windowSize = 8;
    @Value("${mealcheck.analysis.outbox-confirm-timeout-ms:5000}")
    private long confirmTimeoutMs = 5000;
    private record Pending(MealAnalysisOutboxStore.Dispatch dispatch, CorrelationData correlation) { }

    public MealAnalysisOutboxPublisher(MealAnalysisOutboxStore store, RabbitTemplate rabbit, MeterRegistry metrics, BackgroundTaskDispatcher background) {
        this.store = store;
        this.rabbit = rabbit;
        this.metrics = metrics;
        this.background = background;
    }

    @Scheduled(fixedDelayString = "${mealcheck.analysis.outbox-poll-ms:1000}",
            initialDelayString = "${mealcheck.analysis.outbox-initial-delay-ms:0}")
    public void tick() {
        background.submit("analysis-outbox", this::publish);
    }

    public void publish() {
        if (!publishing.compareAndSet(false, true)) return;
        try {
            // Keep the total run bounded and never reserve more work than the current send window.
            int window = Math.max(1, Math.min(20, windowSize));
            long timeout = TimeUnit.MILLISECONDS.toNanos(Math.max(100, Math.min(10000, confirmTimeoutMs)));
            for (int claimed = 0; claimed < 20 && !Thread.currentThread().isInterrupted();) {
                var batch = store.claimBatch(Math.min(window, 20 - claimed));
                if (batch.isEmpty()) return;
                claimed += batch.size();
                var pending = new ArrayList<Pending>(batch.size());
                long deadline = System.nanoTime() + timeout;
                boolean failed = false;
                for (var dispatch : batch) {
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                        failed = true;
                        break;
                    }
                    try {
                        var correlation = new CorrelationData(dispatch.token().toString());
                        var message = MessageBuilder.withBody(dispatch.eventId().toString().getBytes(StandardCharsets.UTF_8))
                                .setContentType("text/plain").setContentEncoding("UTF-8")
                                .setMessageId(dispatch.eventId().toString())
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                        rabbit.send(MealAnalysisRabbitConfiguration.EXCHANGE, MealAnalysisRabbitConfiguration.ROUTING_KEY,
                                message, correlation);
                        pending.add(new Pending(dispatch, correlation));
                    } catch (Exception error) {
                        deferred(error);
                        failed = true;
                        break;
                    }
                }
                // All sends are already in flight. One window shares a deadline, so a missing ACK
                // cannot multiply the wait by the number of messages. No DB work runs on Rabbit threads.
                for (var send : pending) {
                    try {
                        var correlation = send.correlation();
                        var confirm = correlation.getFuture().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        if (!confirm.isAck() || correlation.getReturned() != null) {
                            throw new IllegalStateException("Message not confirmed or not routed");
                        }
                        boolean owned = store.confirmed(send.dispatch());
                        metrics.counter("mealcheck.analysis.outbox", "outcome", owned ? "confirmed" : "fenced").increment();
                    } catch (Exception error) {
                        deferred(error);
                        failed = true;
                        if (error instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                // Every ACK in this window is handled independently; failed/unsent rows recover
                // after their 30s lease. Stop opening windows while the broker or DB is unhealthy.
                if (failed) return;
            }
        } finally {
            publishing.set(false);
        }
    }

    private void deferred(Exception error) {
        metrics.counter("mealcheck.analysis.outbox", "outcome", "retry").increment();
        log.warn("Analysis dispatch deferred type={}", error.getClass().getSimpleName());
    }
}
