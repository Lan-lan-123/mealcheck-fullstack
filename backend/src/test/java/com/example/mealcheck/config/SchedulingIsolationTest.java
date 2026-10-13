package com.example.mealcheck.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class SchedulingIsolationTest {
    @Configuration
    @EnableScheduling
    @Import(AppConfig.class)
    static class Config {
        @Bean MeterRegistry registry() { return new SimpleMeterRegistry(); }
        @Bean SlowTask slowTask() { return new SlowTask(); }
    }
    static class SlowTask {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile String thread;
        @Scheduled(fixedDelay = 100000)
        public void run() throws InterruptedException {
            thread = Thread.currentThread().getName();
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
        }
    }
    @Test void blockedScheduledTaskCannotDelayAssistantTimeout() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var slow = context.getBean(SlowTask.class);
            try {
                assertThat(slow.entered.await(2, TimeUnit.SECONDS)).isTrue();
                var timeout = context.getBean("assistantTimeoutScheduler", ScheduledExecutorService.class);
                assertThat(timeout.schedule(() -> Thread.currentThread().getName(), 10, TimeUnit.MILLISECONDS)
                        .get(1, TimeUnit.SECONDS)).startsWith("assistant-timeout-");
                assertThat(slow.thread).startsWith("background-scheduler-");
            } finally { slow.release.countDown(); }
        }
    }
}
