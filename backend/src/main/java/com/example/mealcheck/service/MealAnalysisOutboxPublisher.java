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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "mealcheck.analysis.dispatch", havingValue = "rabbitmq", matchIfMissing = true)
public class MealAnalysisOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(MealAnalysisOutboxPublisher.class);
    private final MealAnalysisOutboxStore store;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;
    private final BackgroundTaskDispatcher background;

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
        for (int i = 0; i < 20; i++) {
            var dispatch = store.claim();
            if (dispatch == null) return;
            try {
                var correlation = new CorrelationData(dispatch.token().toString());
                var message = MessageBuilder.withBody(dispatch.eventId().toString().getBytes(StandardCharsets.UTF_8))
                        .setContentType("text/plain").setContentEncoding("UTF-8")
                        .setMessageId(dispatch.eventId().toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                rabbit.send(MealAnalysisRabbitConfiguration.EXCHANGE, MealAnalysisRabbitConfiguration.ROUTING_KEY,
                        message, correlation);
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("Message not confirmed or not routed");
                }
                store.confirmed(dispatch);
                metrics.counter("mealcheck.analysis.outbox", "outcome", "confirmed").increment();
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                metrics.counter("mealcheck.analysis.outbox", "outcome", "retry").increment();
                log.warn("Analysis dispatch deferred type={}", error.getClass().getSimpleName());
                // Claim lease expires in 30s; stop this batch to avoid hammering an unavailable broker.
                return;
            }
        }
    }
}
