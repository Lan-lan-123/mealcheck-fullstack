package com.example.mealcheck.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class MealOperationsServiceTest {
    @Test void failureAndRecoveryUpdateHealthWithoutErasingLastSuccess() {
        var jdbc=mock(JdbcTemplate.class); var metrics=new SimpleMeterRegistry();
        var service=new MealOperationsService(jdbc,metrics,new BackgroundTaskDispatcher(Runnable::run));
        ReflectionTestUtils.setField(service,"retentionEnabled",true);
        service.retain();
        double success=metrics.get("mealcheck.retention.last.success.timestamp.seconds").gauge().value();
        when(jdbc.update(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(service::retain).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.get("mealcheck.retention.healthy").gauge().value()).isZero();
        assertThat(metrics.get("mealcheck.retention.last.success.timestamp.seconds").gauge().value()).isEqualTo(success);
        doReturn(0).when(jdbc).update(anyString()); service.resumeRetention();
        assertThat(metrics.get("mealcheck.retention.healthy").gauge().value()).isOne();
    }
    @Test void resumesFullBatchesAndStopsAfterTheBacklogIsDrained() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new MealOperationsService(jdbc, new SimpleMeterRegistry(), new BackgroundTaskDispatcher(Runnable::run));
        ReflectionTestUtils.setField(service, "retentionEnabled", true);
        // One full cache batch means there may be more; a short batch ends resumption.
        when(jdbc.update(anyString())).thenReturn(500, 7);
        service.retain();
        service.resumeRetention();
        service.resumeRetention();
        verify(jdbc, times(2)).update(anyString());
    }
    @Test void databaseFailureIsRetriedByTheResumeTick() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new MealOperationsService(jdbc, new SimpleMeterRegistry(), new BackgroundTaskDispatcher(Runnable::run));
        ReflectionTestUtils.setField(service, "retentionEnabled", true);
        when(jdbc.update(anyString())).thenThrow(new IllegalStateException("unavailable")).thenReturn(0);
        assertThatThrownBy(service::retain).isInstanceOf(IllegalStateException.class);
        service.resumeRetention();
        service.resumeRetention();
        verify(jdbc, times(2)).update(anyString());
    }
}
