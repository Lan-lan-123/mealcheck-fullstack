package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.WeeklyReportJob;
import com.example.mealcheck.repository.WeeklyReportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Service
public class WeeklyReportJobService {
    private final WeeklyReportJobRepository repository;
    private final AppProperties properties;
    private final TaskFailureRecorder failures;
    private final com.example.mealcheck.repository.UserAccountRepository users;

    public WeeklyReportJobService(WeeklyReportJobRepository repository, AppProperties properties,
            TaskFailureRecorder failures, com.example.mealcheck.repository.UserAccountRepository users) {
        this.repository = repository;
        this.properties = properties;
        this.failures = failures;
        this.users = users;
    }

    @Transactional
    public int enqueue(List<Long> userIds, int days, LocalDate reportWeek) {
        LocalDate week = reportWeek == null
                ? AppTime.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : reportWeek;
        LocalDateTime now = AppTime.now();
        int inserted = 0;
        for (Long userId : userIds) {
            if (userId != null) {
                inserted += repository.enqueue(userId, week, Math.max(1, days), now);
            }
        }
        return inserted;
    }

    @Transactional
    public List<WeeklyReportJob> claimBatch() {
        return claimBatch(workerBatchSize());
    }

    @Transactional
    public List<WeeklyReportJob> claimBatch(int availableCapacity) {
        LocalDateTime now = AppTime.now();
        failures.recoverExpiredWeekly(now.minusSeconds(staleLeaseSeconds()), maxAttempts());
        repository.recoverStale(now.minusSeconds(staleLeaseSeconds()), now, maxAttempts());
        if (availableCapacity <= 0) return List.of();
        List<WeeklyReportJob> jobs = repository.findClaimable(now, Math.min(workerBatchSize(), availableCapacity));
        jobs.forEach(job -> {
            job.setStatus(WeeklyReportJob.RUNNING);
            job.setAttempts(job.getAttempts() + 1);
            job.setExecutionToken(java.util.UUID.randomUUID().toString());
            job.setUpdatedAt(now);
        });
        return List.copyOf(jobs);
    }

    @Transactional
    public boolean releaseUndispatched(Long jobId, int attempt, String token) {
        return repository.releaseUndispatched(jobId, attempt, token,
                AppTime.now().plusSeconds(5), AppTime.now(), java.util.UUID.randomUUID().toString()) == 1;
    }

    @Transactional
    public boolean markFailed(Long jobId, int attempt, String token, Throwable error) {
        var candidate = repository.findById(jobId);
        if (candidate.isEmpty() || users.findByIdForUpdate(candidate.get().getUserId()).isEmpty()) return false;
        LocalDateTime now = AppTime.now();
        boolean dead = attempt >= maxAttempts();
        String status = dead ? WeeklyReportJob.DEAD : WeeklyReportJob.RETRY;
        LocalDateTime nextRetryAt = dead ? now : now.plusSeconds(retryDelaySeconds(attempt));
        var failure = TaskFailureClassifier.classify(error, "GENERATE");
        boolean changed = repository.markFailed(
                jobId,
                attempt,
                token,
                status,
                failure.code(),
                nextRetryAt,
                now
        ) == 1;
        if (changed) failures.record("WEEKLY_REPORT", candidate.get().getUserId(), jobId.toString(), null,
                token, attempt, "GENERATE", failure, dead);
        return changed;
    }

    private int workerBatchSize() {
        return Math.max(1, Math.min(100, properties.getWeeklyReport().getWorkerBatchSize()));
    }

    private int maxAttempts() {
        return Math.max(1, Math.min(10, properties.getWeeklyReport().getMaxAttempts()));
    }

    private long staleLeaseSeconds() {
        return Math.max(60L, Math.min(86_400L, properties.getWeeklyReport().getStaleLeaseSeconds()));
    }

    private long retryDelaySeconds(int attempts) {
        return Math.min(1800L, 60L * (1L << Math.min(5, Math.max(0, attempts - 1))));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) return "unknown failure";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
