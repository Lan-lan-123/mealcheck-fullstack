package com.example.mealcheck.service;

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

    public WeeklyReportJobService(WeeklyReportJobRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public int enqueue(List<Long> userIds, int days, LocalDate reportWeek) {
        LocalDate week = reportWeek == null
                ? LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : reportWeek;
        LocalDateTime now = LocalDateTime.now();
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
        LocalDateTime now = LocalDateTime.now();
        repository.recoverStale(now.minusSeconds(staleLeaseSeconds()), now);
        List<WeeklyReportJob> jobs = repository.findClaimable(now, workerBatchSize());
        jobs.forEach(job -> {
            job.setStatus(WeeklyReportJob.RUNNING);
            job.setAttempts(job.getAttempts() + 1);
            job.setUpdatedAt(now);
        });
        return List.copyOf(jobs);
    }

    @Transactional
    public boolean markSucceeded(Long jobId, int attempt) {
        return repository.markSucceeded(jobId, attempt, LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markFailed(Long jobId, int attempt, Throwable error) {
        LocalDateTime now = LocalDateTime.now();
        boolean dead = attempt >= maxAttempts();
        String status = dead ? WeeklyReportJob.DEAD : WeeklyReportJob.RETRY;
        LocalDateTime nextRetryAt = dead ? now : now.plusSeconds(retryDelaySeconds(attempt));
        return repository.markFailed(
                jobId,
                attempt,
                status,
                truncate(error == null ? "unknown failure" : error.getMessage(), 1000),
                nextRetryAt,
                now
        ) == 1;
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
