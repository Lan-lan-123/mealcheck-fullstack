package com.example.mealcheck.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;
import com.example.mealcheck.config.MealAnalysisRabbitConfiguration;

@Service
public class TaskFailureMetrics {
    private final TaskFailureManagementService service;
    private final ObjectProvider<AmqpAdmin> rabbit;
    private final boolean rabbitEnabled;
    private final AtomicLong open=new AtomicLong(),oldest=new AtomicLong(),retrying=new AtomicLong(),deadReady=new AtomicLong(),healthy=new AtomicLong();
    private final AtomicLong actionable=new AtomicLong(),actionableOldest=new AtomicLong();
    public TaskFailureMetrics(TaskFailureManagementService service,ObjectProvider<AmqpAdmin> rabbit,MeterRegistry registry,
            @Value("${mealcheck.analysis.dispatch:rabbitmq}") String dispatch) {
        this.service=service;this.rabbit=rabbit;this.rabbitEnabled="rabbitmq".equals(dispatch);
        Gauge.builder("mealcheck.failure.open",open,AtomicLong::get).register(registry);
        Gauge.builder("mealcheck.failure.oldest.open.seconds",oldest,AtomicLong::get).register(registry);
        Gauge.builder("mealcheck.failure.retrying",retrying,AtomicLong::get).register(registry);
        Gauge.builder("mealcheck.failure.dead.ready",deadReady,AtomicLong::get).register(registry);
        Gauge.builder("mealcheck.failure.monitor.healthy",healthy,AtomicLong::get).register(registry);
        Gauge.builder("mealcheck.failure.actionable",actionable,AtomicLong::get).register(registry);
        Gauge.builder("mealcheck.failure.oldest.actionable.seconds",actionableOldest,AtomicLong::get).register(registry);
    }
    @Scheduled(fixedDelayString="${mealcheck.failure-management.metrics-ms:15000}")
    public void sample() {
        try {
            var summary=service.summary();open.set(summary.open());oldest.set(summary.oldestOpenSeconds());retrying.set(summary.retrying());
            actionable.set(summary.actionable());actionableOldest.set(summary.oldestActionableSeconds());
            if (rabbitEnabled) {
                var admin=rabbit.getIfAvailable();
                var properties=admin == null ? null : admin.getQueueProperties(MealAnalysisRabbitConfiguration.DEAD_QUEUE);
                if (properties == null) { healthy.set(0);return; }
                deadReady.set(((Number)properties.get("QUEUE_MESSAGE_COUNT")).longValue());
                if (properties.get("QUEUE_CONSUMER_COUNT") instanceof Number count && count.intValue()==0) { healthy.set(0);return; }
            }
            healthy.set(1);
        } catch (RuntimeException error) { healthy.set(0); }
    }
}
