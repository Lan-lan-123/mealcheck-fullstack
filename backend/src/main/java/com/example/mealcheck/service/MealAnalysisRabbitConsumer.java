package com.example.mealcheck.service;

import com.example.mealcheck.config.MealAnalysisRabbitConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnExpression("'${mealcheck.analysis.dispatch:rabbitmq}' == 'rabbitmq' && ${mealcheck.analysis.worker-enabled:true}")
public class MealAnalysisRabbitConsumer {
    private final MealAnalysisRequestStore store;
    private final MealAnalysisService analysis;
    private final MeterRegistry metrics;
    private final MealAnalysisLeaseService leases;

    public MealAnalysisRabbitConsumer(MealAnalysisRequestStore store, MealAnalysisService analysis, MeterRegistry metrics,
            MealAnalysisLeaseService leases) {
        this.store = store;
        this.analysis = analysis;
        this.metrics = metrics;
        this.leases = leases;
    }

    @RabbitListener(queues = MealAnalysisRabbitConfiguration.QUEUE, containerFactory = "mealAnalysisListenerFactory",
            concurrency = "${mealcheck.analysis.concurrency:8}")
    public void consume(Message message) {
        UUID eventId;
        try {
            if (message.getBody().length != 36) throw new IllegalArgumentException("Invalid dispatch length");
            eventId = UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            throw new AmqpRejectAndDontRequeueException("Invalid analysis dispatch");
        }
        var work = store.takeMessage(eventId);
        // Duplicate, active, deleted, completed or superseded tasks are safe to acknowledge.
        // A live attempt that later crashes is redispatched by the outbox after its DB lease expires.
        if (work == null) {
            metrics.counter("mealcheck.analysis.deliveries", "outcome", "ignored").increment();
            return;
        }
        long start = System.nanoTime();
        Timer.builder("mealcheck.analysis.queue.wait").publishPercentileHistogram().register(metrics)
                .record(work.queueWaitMillis(),TimeUnit.MILLISECONDS);
        String outcome = "failure";
        String phase = "ANALYZE";
        try (var deadline = analysis.analysisDeadline(work.user()); var lease = leases.track(work)) {
            var prepared = analysis.prepareValidated(work.user(), work.imageReference()==null?work.image():store.loadImage(work), work.goal());
            phase = "SAVE";
            var result = store.complete(work.user(), work.key(), work.token(), prepared);
            outcome = "SUCCEEDED".equals(result.status()) ? "success" : "fenced";
        } catch (RuntimeException error) {
            store.fail(work.user().getId(), work.key(), work.token(), phase, error);
            // Model calls already have bounded retry; do not automatically rebill terminal failures.
            // Do not include original exceptions / model response content in broker error logs.
            throw new AmqpRejectAndDontRequeueException("Analysis failed; retry with original image");
        } finally {
            metrics.counter("mealcheck.analysis.jobs", "outcome", outcome).increment();
            Timer.builder("mealcheck.analysis.duration").tag("outcome", outcome).publishPercentileHistogram()
                    .register(metrics).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
        // store.complete is a proxied transaction: Spring ACK happens after its commit and this return.
    }
}
