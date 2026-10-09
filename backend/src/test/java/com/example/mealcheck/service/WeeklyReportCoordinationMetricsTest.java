package com.example.mealcheck.service;

import com.example.mealcheck.repository.WeeklyReportCoordinationRunRepository;
import com.example.mealcheck.util.AppTime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyReportCoordinationMetricsTest {

    @Test
    void exposesActiveRunCountAndOldestAge() {
        WeeklyReportCoordinationRunRepository repository = mock(WeeklyReportCoordinationRunRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(repository.countByStatus("RUNNING")).thenReturn(3L);
        when(repository.findOldestRunningStartedAt()).thenReturn(AppTime.now().minusSeconds(120));

        WeeklyReportCoordinationMetrics metrics = new WeeklyReportCoordinationMetrics(repository, registry);

        assertThat(registry.get("mealcheck.weekly.report.coordination.active.runs")
                .gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("mealcheck.weekly.report.coordination.oldest.active.age")
                .gauge().value()).isBetween(119.0, 121.0);
        // Gauges hold weak references; keep their owner alive through both reads.
        Reference.reachabilityFence(metrics);
    }
}
