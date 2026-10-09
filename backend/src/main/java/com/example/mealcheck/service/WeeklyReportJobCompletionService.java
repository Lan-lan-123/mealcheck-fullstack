package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.WeeklyReportSnapshot;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.WeeklyReportJobRepository;
import com.example.mealcheck.repository.WeeklyReportSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class WeeklyReportJobCompletionService {
    private final WeeklyReportJobRepository jobs;
    private final WeeklyReportSnapshotRepository snapshots;
    private final UserAccountRepository users;
    private final ObjectMapper mapper;
    private final TaskFailureRecorder failures;

    public WeeklyReportJobCompletionService(WeeklyReportJobRepository jobs,
            WeeklyReportSnapshotRepository snapshots, UserAccountRepository users, ObjectMapper mapper, TaskFailureRecorder failures) {
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.users = users;
        this.mapper = mapper;
        this.failures = failures;
    }

    @Transactional
    public boolean complete(Long jobId, int attempt, String token, WeeklyReportResponse report) {
        // The conditional UPDATE locks the job until the snapshot and status commit together.
        // A recovered, deleted or already completed attempt must never publish a snapshot.
        var candidate = jobs.findById(jobId);
        if (candidate.isEmpty()) return false;
        var job = candidate.get();
        // Meal writes use the same account lock before invalidating jobs.
        if (users.findByIdForUpdate(job.getUserId()).isEmpty()) return false;
        if (jobs.markSucceeded(jobId, attempt, token, AppTime.now()) != 1) return false;
        WeeklyReportSnapshot snapshot = new WeeklyReportSnapshot();
        snapshot.setJobId(jobId);
        snapshot.setUser(users.getReferenceById(job.getUserId()));
        snapshot.setDays(job.getDays());
        snapshot.setPeriodEnd(AppTime.businessDate(job.getCreatedAt()));
        snapshot.setPeriodStart(snapshot.getPeriodEnd().minusDays(job.getDays() - 1L));
        report.setPeriodStart(snapshot.getPeriodStart());
        report.setPeriodEnd(snapshot.getPeriodEnd());
        report.setDays(job.getDays());
        try {
            snapshot.setReportJson(mapper.writeValueAsString(report));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize weekly report", error);
        }
        snapshots.saveAndFlush(snapshot);
        failures.recovered("WEEKLY_REPORT", job.getUserId(), jobId.toString());
        return true;
    }
}
