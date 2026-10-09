package com.example.mealcheck.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MealOperationsService {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final AtomicLong pending=new AtomicLong(),oldest=new AtomicLong(),expired=new AtomicLong();
    private final BackgroundTaskDispatcher background;
    @Value("${mealcheck.retention.enabled:true}") private boolean retentionEnabled;
    @Value("${mealcheck.retention.failed-image-days:7}") private int imageDays;
    @Value("${mealcheck.retention.failed-request-days:90}") private int requestDays;
    @Value("${mealcheck.retention.stale-report-days:30}") private int reportDays;
    @Value("${mealcheck.retention.audit-days:180}") private int auditDays;
    public MealOperationsService(JdbcTemplate jdbc,MeterRegistry metrics,BackgroundTaskDispatcher background) {
        this.jdbc=jdbc;this.metrics=metrics;
        this.background=background;
        Gauge.builder("mealcheck.analysis.pending",pending,AtomicLong::get).register(metrics);
        Gauge.builder("mealcheck.analysis.oldest.active.seconds",oldest,AtomicLong::get).register(metrics);
        Gauge.builder("mealcheck.analysis.expired.leases",expired,AtomicLong::get).register(metrics);
    }
    @Scheduled(fixedDelayString="${mealcheck.analysis.metrics-ms:15000}")
    public void sample() {
        var row=jdbc.queryForMap("""
            SELECT count(*) FILTER(WHERE status='PENDING') AS pending,
              coalesce(extract(epoch from CURRENT_TIMESTAMP-min(created_at) FILTER(WHERE status IN ('PENDING','PROCESSING'))),0)::bigint AS oldest,
              count(*) FILTER(WHERE status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP) AS expired
            FROM meal_analysis_requests WHERE status IN ('PENDING','PROCESSING')
            """);
        pending.set(((Number)row.get("pending")).longValue());oldest.set(((Number)row.get("oldest")).longValue());expired.set(((Number)row.get("expired")).longValue());
    }
    @Scheduled(cron="${mealcheck.retention.cron:0 10 3 * * *}")
    public void retentionTick() { background.submit("retention", this::retain); }

    public void retain() {
        if (!retentionEnabled) return;
        metrics.counter("mealcheck.retention.rows","kind","deleted-result-cache").increment(jdbc.update(
            "UPDATE meal_analysis_requests SET response_json=NULL WHERE status='SUCCEEDED' AND record_id IS NULL AND response_json IS NOT NULL"));
        // Keep successful keys/tombstones: deleting them would break idempotency.
        clean("images", "UPDATE meal_analysis_requests SET input_image=NULL WHERE status='FAILED' AND updated_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day') AND input_image IS NOT NULL",imageDays);
        clean("requests", "DELETE FROM meal_analysis_requests WHERE status='FAILED' AND updated_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day')",requestDays);
        clean("reports", "DELETE FROM weekly_report_jobs WHERE status='STALE' AND updated_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day')",reportDays);
        clean("audit", "DELETE FROM admin_audit_logs WHERE created_at<CURRENT_TIMESTAMP-(? * INTERVAL '1 day')",auditDays);
    }
    private void clean(String kind,String sql,int days) {
        if (days<1) return; // zero disables this policy.
        metrics.counter("mealcheck.retention.rows","kind",kind).increment(jdbc.update(sql,days));
    }
}
