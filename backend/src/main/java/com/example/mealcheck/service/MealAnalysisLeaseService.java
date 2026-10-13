package com.example.mealcheck.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/** Keep live workers leased; a crashed process stops renewing and can be recovered promptly. */
@Service
public class MealAnalysisLeaseService {
    private static final Logger log = LoggerFactory.getLogger(MealAnalysisLeaseService.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final int leaseSeconds;
    private final ConcurrentHashMap<String, MealAnalysisRequestStore.Work> active = new ConcurrentHashMap<>();

    public MealAnalysisLeaseService(JdbcTemplate jdbc, MeterRegistry metrics,
            @Value("${mealcheck.analysis.lease-seconds:90}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.leaseSeconds = boundedSeconds(leaseSeconds);
    }

    static int boundedSeconds(int seconds) { return Math.max(30, Math.min(1200, seconds)); }

    public Lease track(MealAnalysisRequestStore.Work work) {
        active.put(work.token(), work);
        return new Lease(work);
    }

    public final class Lease implements AutoCloseable {
        private final MealAnalysisRequestStore.Work work;
        private Lease(MealAnalysisRequestStore.Work work) { this.work = work; }
        @Override public void close() { active.remove(work.token(), work); }
    }

    @Scheduled(fixedDelay = 10000)
    public void renew() {
        active.forEach((token, work) -> {
            try {
                int updated = jdbc.update("""
                    UPDATE meal_analysis_requests
                    SET lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                    WHERE user_id=? AND request_key=? AND attempt_token=?
                      AND status='PROCESSING' AND lease_until > CURRENT_TIMESTAMP
                    """, leaseSeconds, work.user().getId(), work.key(), token);
                if (updated == 0) active.remove(token, work);
                metrics.counter("mealcheck.analysis.lease", "outcome", updated == 1 ? "renewed" : "lost").increment();
            } catch (RuntimeException error) {
                // Retain registration for a later tick. An expired or replaced token is never revived.
                metrics.counter("mealcheck.analysis.lease", "outcome", "error").increment();
                log.warn("Analysis lease renewal failed type={}", error.getClass().getSimpleName());
            }
        });
    }
}
