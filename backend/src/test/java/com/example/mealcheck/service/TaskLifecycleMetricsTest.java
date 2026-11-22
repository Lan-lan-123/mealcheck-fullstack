package com.example.mealcheck.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskLifecycleMetricsTest {
    @Test void failedSamplesPreserveLastKnownOccupancyAndExposeFailure() {
        var jdbc = mock(JdbcTemplate.class);
        var registry = new SimpleMeterRegistry();
        var service = new TaskLifecycleMetrics(jdbc, registry, new BackgroundTaskDispatcher(Runnable::run));
        when(jdbc.queryForMap(contains("octet_length"))).thenReturn(Map.of("bytes", 100L,"failed",60L,
                "oldest",40L,"failed_oldest",30L,"refs",2L,"recovery",1L));
        when(jdbc.queryForMap(contains("JOIN meal_analysis_requests"))).thenReturn(Map.of("total",3L,"oldest",10L));
        service.sample();
        assertThat(registry.get("mealcheck.lifecycle.payload.bytes").gauge().value()).isEqualTo(100);
        assertThat(registry.get("mealcheck.lifecycle.monitor.healthy").gauge().value()).isOne();
        when(jdbc.queryForMap(contains("octet_length"))).thenThrow(new IllegalStateException("offline"));
        service.sample();
        assertThat(registry.get("mealcheck.lifecycle.payload.bytes").gauge().value()).isEqualTo(100);
        assertThat(registry.get("mealcheck.lifecycle.monitor.healthy").gauge().value()).isZero();
        assertThat(registry.get("mealcheck.lifecycle.monitor.last.success.timestamp.seconds").gauge().value()).isPositive();
    }

    @Test void databaseStatsHaveAnIndependentHealthSignal() {
        var jdbc = mock(JdbcTemplate.class); var registry = new SimpleMeterRegistry();
        var service = new TaskLifecycleMetrics(jdbc, registry, new BackgroundTaskDispatcher(Runnable::run));
        when(jdbc.queryForMap(contains("pg_stat_user_tables"))).thenReturn(Map.of("bytes",900L,"tables",600L,"dead",10L));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(200L);
        service.sampleDatabase();
        assertThat(registry.get("mealcheck.lifecycle.database.wal.bytes").functionCounter().count()).isEqualTo(200);
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenThrow(new IllegalStateException("permission"));
        service.sampleDatabase();
        assertThat(registry.get("mealcheck.lifecycle.database.monitor.healthy").gauge().value()).isZero();
        assertThat(registry.get("mealcheck.lifecycle.database.bytes").gauge().value()).isEqualTo(900);
    }
}
