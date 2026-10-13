package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.dto.WeeklyReportJobResponse;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.WeeklyReportJob;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.WeeklyReportJobRepository;
import com.example.mealcheck.repository.WeeklyReportSnapshotRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class WeeklyReportRequestService {
    private final BehaviorTrackingService tracking;
    private final UserAccountRepository users;
    private final WeeklyReportJobRepository jobs;
    private final WeeklyReportSnapshotRepository snapshots;
    private final RateLimitService rateLimits;
    private final ObjectMapper mapper;

    public WeeklyReportRequestService(UserAccountRepository users, WeeklyReportJobRepository jobs,
            WeeklyReportSnapshotRepository snapshots, RateLimitService rateLimits, ObjectMapper mapper, BehaviorTrackingService tracking) {
        this.tracking = tracking;
        this.users = users;
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.rateLimits = rateLimits;
        this.mapper = mapper;
    }

    @Transactional
    public WeeklyReportJobResponse request(UserPrincipal principal, int days) {
        if (days < 1 || days > 30) throw new IllegalArgumentException("统计天数必须在 1 到 30 之间");
        LocalDateTime now = AppTime.now();
        LocalDate date = AppTime.today();
        // Serialize submissions per account: duplicate clicks do not spend quota twice.
        users.findByIdForUpdate(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        var existing = jobs.findByUserIdAndReportWeekAndDays(principal.getId(), date, days);
        if (existing.isPresent()) return response(existing.get());
        var limit = rateLimits.consumeWeeklyReport(principal.getId().toString());
        if (limit.unavailable()) throw new IllegalStateException("周报限流服务暂时不可用，请稍后重试。");
        if (!limit.allowed()) throw new RateLimitExceededException(
                "周报生成次数已达限额，请稍后再试。", limit.limit(), limit.remaining(), limit.retryAfter());
        jobs.enqueue(principal.getId(), date, days, now);
        return response(jobs.findByUserIdAndReportWeekAndDays(principal.getId(), date, days).orElseThrow());
    }

    @Transactional(readOnly = true)
    public WeeklyReportJobResponse status(UserPrincipal principal, Long id) {
        return response(jobs.findByIdAndUserId(id, principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "周报任务不存在")));
    }

    private WeeklyReportJobResponse response(WeeklyReportJob job) {
        WeeklyReportResponse report = WeeklyReportJob.SUCCEEDED.equals(job.getStatus())
                ? snapshots.findByJobId(job.getId())
                    .map(snapshot -> Jsons.fromJson(mapper, snapshot.getReportJson(), WeeklyReportResponse.class))
                    .orElseThrow(() -> new IllegalStateException("周报结果暂时不可用"))
                : null;
        if(report!=null && tracking!=null && report.getPeriodStart()!=null && report.getPeriodEnd()!=null) report.setTracking(tracking.summary(job.getUserId(),report.getPeriodStart(),report.getPeriodEnd()));
        return new WeeklyReportJobResponse(job.getId(), job.getStatus(), job.getDays(), job.getAttempts(),
                WeeklyReportJob.RETRY.equals(job.getStatus()) ? job.getNextRetryAt() : null, report);
    }
}
