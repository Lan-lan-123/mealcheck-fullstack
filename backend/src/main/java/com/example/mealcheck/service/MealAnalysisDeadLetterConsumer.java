package com.example.mealcheck.service;

import com.example.mealcheck.config.MealAnalysisRabbitConfiguration;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@ConditionalOnExpression("'${mealcheck.analysis.dispatch:rabbitmq}' == 'rabbitmq' && ${mealcheck.failure-management.dead-letter-archive-enabled:true}")
public class MealAnalysisDeadLetterConsumer {
    private final MealAnalysisDeadLetterArchive archive;
    private final MeterRegistry metrics;
    public MealAnalysisDeadLetterConsumer(MealAnalysisDeadLetterArchive archive, MeterRegistry metrics) {
        this.archive = archive; this.metrics = metrics;
    }
    @RabbitListener(queues=MealAnalysisRabbitConfiguration.DEAD_QUEUE,containerFactory="deadLetterArchiveListenerFactory",concurrency="1")
    public void consume(Message message) throws java.security.NoSuchAlgorithmException {
        UUID event = null;
        if (message.getBody().length == 36) {
            try { event = UUID.fromString(new String(message.getBody(),StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException ignored) { }
        }
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.getBody()));
        try {
            archive.archive(event,fingerprint); // A proxied transaction commits before Spring AUTO ACK.
            metrics.counter("mealcheck.failure.archive","outcome","success").increment();
        } catch (RuntimeException error) {
            metrics.counter("mealcheck.failure.archive","outcome","failure").increment();
            throw error;
        }
    }
}
