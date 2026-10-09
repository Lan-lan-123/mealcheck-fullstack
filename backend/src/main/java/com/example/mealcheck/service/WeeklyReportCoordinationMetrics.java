package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.repository.WeeklyReportCoordinationRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class WeeklyReportCoordinationMetrics {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportCoordinationMetrics.class);

    private final WeeklyReportCoordinationRunRepository runRepository;

    public WeeklyReportCoordinationMetrics(WeeklyReportCoordinationRunRepository runRepository,
                                           MeterRegistry registry) {
        this.runRepository = runRepository;
        Gauge.builder("mealcheck.weekly.report.coordination.active.runs", this,
                        WeeklyReportCoordinationMetrics::activeRuns)
                .description("Number of weekly report coordination runs currently active")
                .register(registry);
        Gauge.builder("mealcheck.weekly.report.coordination.oldest.active.age", this,
                        WeeklyReportCoordinationMetrics::oldestActiveAgeSeconds)
                .baseUnit("seconds")
                .description("Age in seconds of the oldest active weekly report coordination run")
                .register(registry);
    }

    double activeRuns() {
        try {
            return runRepository.countByStatus("RUNNING");
        } catch (RuntimeException error) {
            log.debug("Unable to read active weekly report coordination count: {}", error.getMessage());
            return Double.NaN;
        }
    }

    double oldestActiveAgeSeconds() {
        try {
            LocalDateTime startedAt = runRepository.findOldestRunningStartedAt();
            if (startedAt == null) {
                return 0.0;
            }
            return Math.max(0L, Duration.between(startedAt, AppTime.now()).toSeconds());
        } catch (RuntimeException error) {
            log.debug("Unable to read oldest weekly report coordination age: {}", error.getMessage());
            return Double.NaN;
        }
    }
}
