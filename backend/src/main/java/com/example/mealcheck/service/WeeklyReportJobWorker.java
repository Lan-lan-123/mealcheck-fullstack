package com.example.mealcheck.service;

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

    public WeeklyReportJobWorker(WeeklyReportJobService jobService,
                                 WeeklyReportService weeklyReportService,
                                 @Qualifier("weeklyReportExecutor") Executor executor,
                                 ApplicationObservability observability) {
        this.jobService = jobService;
        this.weeklyReportService = weeklyReportService;
        this.executor = executor;
        this.observability = observability;
    }

    @Scheduled(fixedDelayString = "${mealcheck.weekly-report.worker-delay-millis:5000}")
    public void poll() {
        List<WeeklyReportJob> jobs = jobService.claimBatch();
        for (WeeklyReportJob job : jobs) {
            try {
                executor.execute(() -> execute(job.getId(), job.getUserId(), job.getDays(), job.getAttempts()));
            } catch (RejectedExecutionException error) {
                boolean updated = jobService.markFailed(job.getId(), job.getAttempts(), error);
                observability.recordWeeklyReportJob("rejected");
                log.warn("Weekly report job dispatch rejected. jobId={}, stateUpdated={}", job.getId(), updated);
            }
        }
    }

    private void execute(Long jobId, Long userId, int days, int attempt) {
        try {
            weeklyReportService.generateAndStore(userId, days);
            boolean updated = jobService.markSucceeded(jobId, attempt);
            observability.recordWeeklyReport(updated ? "success" : "stale", 1);
            observability.recordWeeklyReportJob(updated ? "success" : "stale");
            if (!updated) {
                log.warn("Ignored stale weekly report success. jobId={}, attempt={}", jobId, attempt);
            }
        } catch (Exception error) {
            boolean updated = jobService.markFailed(jobId, attempt, error);
            observability.recordWeeklyReport("failure", 1);
            observability.recordWeeklyReportJob(updated ? "failure" : "stale");
            log.warn("Weekly report job failed. jobId={}, userId={}, attempt={}, stateUpdated={}: {}",
                    jobId, userId, attempt, updated, error.getMessage());
        }
    }
}
