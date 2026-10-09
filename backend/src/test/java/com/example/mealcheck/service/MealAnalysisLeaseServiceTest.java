package com.example.mealcheck.service;

import com.example.mealcheck.security.UserPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MealAnalysisLeaseServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MealAnalysisLeaseService leases = new MealAnalysisLeaseService(jdbc, metrics, 90);

    private MealAnalysisRequestStore.Work work() {
        var user = mock(UserPrincipal.class);
        when(user.getId()).thenReturn(7L);
        return new MealAnalysisRequestStore.Work(user, "request-key-0000001", "attempt-token", "balanced", null, 0);
    }

    @Test void renewsOnlyWhileWorkIsTrackedAndStopsOnCompletion() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        try (var lease = leases.track(work())) { leases.renew(); }
        leases.renew();
        verify(jdbc, times(1)).update(contains("AND status='PROCESSING' AND lease_until > CURRENT_TIMESTAMP"),
                eq(90), eq(7L), eq("request-key-0000001"), eq("attempt-token"));
        assertThat(metrics.get("mealcheck.analysis.lease").tag("outcome", "renewed").counter().count()).isEqualTo(1);
    }

    @Test void doesNotKeepRenewingAnExpiredOrReplacedAttempt() {
        try (var lease = leases.track(work())) { leases.renew(); leases.renew(); }
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
        assertThat(metrics.get("mealcheck.analysis.lease").tag("outcome", "lost").counter().count()).isEqualTo(1);
    }

    @Test void retriesTransientDatabaseFailureOnNextHeartbeat() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .thenReturn(1);
        try (var lease = leases.track(work())) { leases.renew(); leases.renew(); }
        assertThat(metrics.get("mealcheck.analysis.lease").tag("outcome", "error").counter().count()).isEqualTo(1);
        assertThat(metrics.get("mealcheck.analysis.lease").tag("outcome", "renewed").counter().count()).isEqualTo(1);
    }

    @Test void keepsAtLeastThreeHeartbeatIntervalsAndBoundsConfiguration() {
        assertThat(MealAnalysisLeaseService.boundedSeconds(1)).isEqualTo(30);
        assertThat(MealAnalysisLeaseService.boundedSeconds(90)).isEqualTo(90);
        assertThat(MealAnalysisLeaseService.boundedSeconds(Integer.MAX_VALUE)).isEqualTo(1200);
    }
}
