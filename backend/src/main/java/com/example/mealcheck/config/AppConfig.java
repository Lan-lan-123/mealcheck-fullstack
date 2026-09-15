package com.example.mealcheck.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {
    @Bean
    public HttpClient httpClient(AppProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                .build();
    }

    @Bean(name = "weeklyReportExecutor")
    public Executor weeklyReportExecutor(AppProperties properties, MeterRegistry registry) {
        AppProperties.WeeklyReport weekly = properties.getWeeklyReport();
        int coreSize = Math.max(1, weekly.getExecutorCorePoolSize());
        return instrumentedExecutor(
                "weekly-report",
                coreSize,
                Math.max(coreSize, weekly.getExecutorMaxPoolSize()),
                Math.max(1, weekly.getExecutorQueueCapacity()),
                "weekly-report-",
                registry
        );
    }

    @Bean(name = "weeklyReportAgentExecutor")
    public Executor weeklyReportAgentExecutor(AppProperties properties, MeterRegistry registry) {
        AppProperties.WeeklyReport weekly = properties.getWeeklyReport();
        int coreSize = Math.max(2, weekly.getAgentExecutorCorePoolSize());
        return instrumentedExecutor(
                "weekly-report-agent",
                coreSize,
                Math.max(coreSize, weekly.getAgentExecutorMaxPoolSize()),
                Math.max(1, weekly.getAgentExecutorQueueCapacity()),
                "weekly-report-agent-",
                registry
        );
    }

    @Bean(name = "assistantDbExecutor", destroyMethod = "shutdown")
    public ExecutorService assistantDbExecutor(AppProperties properties, MeterRegistry registry) {
        AppProperties.AssistantTools tools = properties.getAssistantTools();
        return instrumentedFixedExecutor(
                "assistant-db",
                Math.max(2, tools.getDbPoolSize()),
                Math.max(1, tools.getDbQueueCapacity()),
                "assistant-db-",
                registry
        );
    }

    @Bean(name = "assistantHttpExecutor", destroyMethod = "shutdown")
    public ExecutorService assistantHttpExecutor(AppProperties properties, MeterRegistry registry) {
        AppProperties.AssistantTools tools = properties.getAssistantTools();
        return instrumentedFixedExecutor(
                "assistant-http",
                Math.max(2, tools.getHttpPoolSize()),
                Math.max(1, tools.getHttpQueueCapacity()),
                "assistant-http-",
                registry
        );
    }

    @Bean(name = "assistantRagExecutor", destroyMethod = "shutdown")
    public ExecutorService assistantRagExecutor(AppProperties properties, MeterRegistry registry) {
        AppProperties.AssistantTools tools = properties.getAssistantTools();
        return instrumentedFixedExecutor(
                "assistant-rag",
                Math.max(2, tools.getRagPoolSize()),
                Math.max(1, tools.getRagQueueCapacity()),
                "assistant-rag-",
                registry
        );
    }

    @Bean(name = "assistantBackgroundExecutor", destroyMethod = "shutdown")
    public ExecutorService assistantBackgroundExecutor(AppProperties properties, MeterRegistry registry) {
        AppProperties.AssistantTools tools = properties.getAssistantTools();
        return instrumentedFixedExecutor(
                "assistant-background",
                Math.max(1, tools.getBackgroundPoolSize()),
                Math.max(1, tools.getBackgroundQueueCapacity()),
                "assistant-background-",
                registry
        );
    }

    @Bean(name = "assistantTimeoutScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService assistantTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(threadFactory("assistant-timeout-"));
    }

    private ThreadPoolTaskExecutor instrumentedExecutor(String name,
                                                        int coreSize,
                                                        int maxSize,
                                                        int queueCapacity,
                                                        String threadPrefix,
                                                        MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setTaskDecorator(taskDecorator(name, registry));
        Counter rejected = Counter.builder("mealcheck.executor.rejected")
                .tag("executor", name)
                .register(registry);
        executor.setRejectedExecutionHandler((task, pool) -> {
            rejected.increment();
            throw new RejectedExecutionException("Executor " + name + " is saturated");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        bindExecutorGauges(name, executor, registry);
        return executor;
    }

    private ExecutorService instrumentedFixedExecutor(String name,
                                                       int poolSize,
                                                       int queueCapacity,
                                                       String threadPrefix,
                                                       MeterRegistry registry) {
        Counter rejected = Counter.builder("mealcheck.executor.rejected")
                .tag("executor", name)
                .register(registry);
        org.springframework.core.task.TaskDecorator decorator = taskDecorator(name, registry);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory(threadPrefix),
                (task, pool) -> {
                    rejected.increment();
                    throw new RejectedExecutionException("Executor " + name + " is saturated");
                }
        ) {
            @Override
            public void execute(Runnable command) {
                super.execute(decorator.decorate(command));
            }
        };
        executor.prestartAllCoreThreads();
        bindExecutorGauges(name, executor, registry);
        return executor;
    }

    private ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private org.springframework.core.task.TaskDecorator taskDecorator(String name, MeterRegistry registry) {
        Timer waitTimer = Timer.builder("mealcheck.executor.task.wait")
                .tag("executor", name)
                .register(registry);
        Timer durationTimer = Timer.builder("mealcheck.executor.task.duration")
                .tag("executor", name)
                .register(registry);
        return task -> {
            long submittedAt = System.nanoTime();
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                waitTimer.record(Math.max(0L, System.nanoTime() - submittedAt), TimeUnit.NANOSECONDS);
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(context);
                }
                long startedAt = System.nanoTime();
                try {
                    task.run();
                } finally {
                    durationTimer.record(Math.max(0L, System.nanoTime() - startedAt), TimeUnit.NANOSECONDS);
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }

    private void bindExecutorGauges(String name, ThreadPoolTaskExecutor executor, MeterRegistry registry) {
        Gauge.builder("mealcheck.executor.pool.size", executor, ThreadPoolTaskExecutor::getPoolSize)
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.active", executor, ThreadPoolTaskExecutor::getActiveCount)
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.queue.size", executor,
                        value -> value.getThreadPoolExecutor().getQueue().size())
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.queue.remaining", executor,
                        value -> value.getThreadPoolExecutor().getQueue().remainingCapacity())
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.completed", executor,
                        value -> value.getThreadPoolExecutor().getCompletedTaskCount())
                .tag("executor", name).register(registry);
    }

    private void bindExecutorGauges(String name, ThreadPoolExecutor executor, MeterRegistry registry) {
        Gauge.builder("mealcheck.executor.pool.size", executor, ThreadPoolExecutor::getPoolSize)
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.active", executor, ThreadPoolExecutor::getActiveCount)
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.queue.size", executor, value -> value.getQueue().size())
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.queue.remaining", executor, value -> value.getQueue().remainingCapacity())
                .tag("executor", name).register(registry);
        Gauge.builder("mealcheck.executor.completed", executor, ThreadPoolExecutor::getCompletedTaskCount)
                .tag("executor", name).register(registry);
    }
}
