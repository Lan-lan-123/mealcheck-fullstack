package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiCallConcurrencyTest {
    @Test void deadlineAndUnavailableStorePreventUnaccountedCalls() {
        var store=mock(AiCallPermitStore.class);
        var gate=new AiCallConcurrency(store,new SimpleMeterRegistry(),1);
        assertThatThrownBy(()->gate.acquire(Duration.ofMillis(10))).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        when(store.acquire(anyInt(),anyLong())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->gate.acquire(Duration.ofSeconds(1))).isInstanceOf(AiBudgetService.BudgetUnavailableException.class);
    }
    @Test void waitCanAcquireLaterAndReleaseFailureRetainsCrashLease() {
        var store=mock(AiCallPermitStore.class);var metrics=new SimpleMeterRegistry();
        var gate=new AiCallConcurrency(store,metrics,2);var id=UUID.randomUUID();
        when(store.acquire(eq(2),anyLong())).thenReturn(null,id);
        assertThat(gate.acquire(Duration.ofSeconds(1))).isEqualTo(id);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(store).release(id);
        gate.release(id);
        assertThat(metrics.get("mealcheck.ai.concurrency.release.deferred").counter().count()).isEqualTo(1);
    }
}
