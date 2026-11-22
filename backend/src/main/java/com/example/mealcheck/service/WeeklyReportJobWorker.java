package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.WeeklyReportJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WeeklyReportJobWorker {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportJobWorker.class);

    private final WeeklyReportJobService jobService;
    private final WeeklyReportService weeklyReportService;
    private final Executor executor;
    private final ApplicationObservability observability;
    private final WeeklyReportJobCompletionService completionService;
    private final AtomicInteger dispatched = new AtomicInteger();
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.operation-timeout-seconds:120}")
    private int operationTimeoutSeconds = 120;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.weekly-report.worker-batch-size:4}")
    private int dispatchBatchSize = 4;

    public WeeklyReportJobWorker(WeeklyReportJobService jobService,
                                 WeeklyReportService weeklyReportService,
                                 @Qualifier("weeklyReportExecutor") Executor executor,
                                 ApplicationObservability observability,
                                 WeeklyReportJobCompletionService completionService) {
        this.jobService = jobService;
        this.weeklyReportService = weeklyReportService;
        this.executor = executor;
        this.observability = observability;
        this.completionService = completionService;
    }

    @Scheduled(fixedDelayString = "${mealcheck.weekly-report.worker-delay-millis:5000}")
    public synchronized void poll() {
        int submissions = Math.min(Math.max(1, dispatchBatchSize), Math.max(0, concurrency() - dispatched.get()));
        for (int i = 0; i < submissions; i++) {
            dispatched.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        // Queue only a bounded number of dispatchers, never a claimed job.
                        // Its lease and attempt begin after an execution thread is available.
                        for (WeeklyReportJob job : jobService.claimBatch(1)) execute(job);
                    } catch (RuntimeException error) {
                        observability.recordWeeklyReportJob("claim-failed");
                        log.warn("Weekly report claim failed type={}", error.getClass().getSimpleName());
                    } finally {
                        dispatched.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException error) {
                dispatched.decrementAndGet();
                observability.recordWeeklyReportJob("rejected");
                log.warn("Weekly report dispatcher rejected; no job was claimed");
                break;
            }
        }
    }

    private int concurrency() {
        if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tasks) {
            return tasks.getMaxPoolSize();
        }
        return 1;
    }

    private void execute(WeeklyReportJob job) {
        Long jobId = job.getId();
        Long userId = job.getUserId();
        int attempt = job.getAttempts();
        String token = job.getExecutionToken();
        try (var deadline = AiCallContext.open(userId, java.time.Duration.ofSeconds(Math.max(1, operationTimeoutSeconds)));
             var background = AiCallContext.background()) {
            // A process pause or invalidation may have replaced the claim before execution.
            if (!jobService.beginExecution(jobId, attempt, token)) {
                observability.recordWeeklyReportJob("stale");
                return;
            }
            AiCallContext.checkExecution();
            WeeklyReportResponse response = weeklyReportService.generateForJob(
                    userId, job.getDays(), jobId, job.getReportWeek() == null
                            ? AppTime.businessDate(job.getCreatedAt()) : job.getReportWeek());
            AiCallContext.checkExecution();
            boolean updated = completionService.complete(jobId, attempt, token, response);
            String outcome = coordinationOutcome(response);
            observability.recordWeeklyReport(updated ? outcome : "stale", 1);
            observability.recordWeeklyReportJob(updated ? outcome : "stale");
            if (!updated) {
                log.warn("Ignored stale weekly report success. jobId={}, attempt={}", jobId, attempt);
            }
        } catch (Exception error) {
            boolean updated = jobService.markFailed(jobId, attempt, token, error);
            observability.recordWeeklyReport("failure", 1);
            observability.recordWeeklyReportJob(updated ? "failure" : "stale");
            log.warn("Weekly report job failed. jobId={}, userId={}, attempt={}, stateUpdated={}: {}",
                    jobId, userId, attempt, updated, error.getMessage());
        }
    }

    private String coordinationOutcome(WeeklyReportResponse response) {
        return response != null && "MULTI_AGENT".equals(response.getGenerationMode())
                ? "success"
                : "degraded";
    }
}
