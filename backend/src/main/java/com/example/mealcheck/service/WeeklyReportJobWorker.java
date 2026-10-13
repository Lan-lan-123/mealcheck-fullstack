package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.WeeklyReportJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class WeeklyReportJobWorker {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportJobWorker.class);

    private final WeeklyReportJobService jobService;
    private final WeeklyReportService weeklyReportService;
    private final Executor executor;
    private final ApplicationObservability observability;
    private final WeeklyReportJobCompletionService completionService;
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai-budget.operation-timeout-seconds:120}")
    private int operationTimeoutSeconds = 120;

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
    public void poll() {
        List<WeeklyReportJob> jobs = jobService.claimBatch(availableCapacity());
        for (WeeklyReportJob job : jobs) {
            try {
                executor.execute(() -> execute(job));
            } catch (RejectedExecutionException error) {
                boolean updated = jobService.releaseUndispatched(job.getId(), job.getAttempts(), job.getExecutionToken());
                observability.recordWeeklyReportJob("rejected");
                log.warn("Weekly report job dispatch rejected. jobId={}, stateUpdated={}", job.getId(), updated);
            }
        }
    }

    private int availableCapacity() {
        if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tasks) {
            var pool = tasks.getThreadPoolExecutor();
            return Math.max(0, pool.getMaximumPoolSize() - pool.getActiveCount() + pool.getQueue().remainingCapacity());
        }
        return 1;
    }

    private void execute(WeeklyReportJob job) {
        Long jobId = job.getId();
        Long userId = job.getUserId();
        int attempt = job.getAttempts();
        String token = job.getExecutionToken();
        try (var deadline = AiCallContext.open(userId, java.time.Duration.ofSeconds(operationTimeoutSeconds));
             var background = AiCallContext.background()) {
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
