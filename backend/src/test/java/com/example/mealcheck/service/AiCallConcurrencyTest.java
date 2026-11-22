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
        when(store.enqueue(anyBoolean(),anyLong())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->gate.acquire(Duration.ofSeconds(1))).isInstanceOf(AiBudgetService.BudgetUnavailableException.class);
    }
    @Test void waitCanAcquireLaterAndReleaseFailureRetainsCrashLease() {
        var store=mock(AiCallPermitStore.class);var metrics=new SimpleMeterRegistry();
        var gate=new AiCallConcurrency(store,metrics,2);var id=UUID.randomUUID();
        UUID ticket=UUID.randomUUID();when(store.enqueue(anyBoolean(),anyLong())).thenReturn(ticket);
        when(store.tryAcquireQueued(eq(2),anyLong(),eq(false),eq(ticket))).thenReturn(new AiCallPermitStore.Admission(null,"concurrency",0),new AiCallPermitStore.Admission(id,"accepted",0));
        assertThat(gate.acquire(Duration.ofSeconds(1))).isEqualTo(id);
        verify(store).cancelWaiter(ticket);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(store).release(id);
        gate.release(id);
        assertThat(metrics.get("mealcheck.ai.concurrency.release.deferred").counter().count()).isEqualTo(1);
    }
    @Test void interruptionRemovesQueuedTicketWithoutDispatch() {
        var store=mock(AiCallPermitStore.class);var ticket=UUID.randomUUID();
        when(store.enqueue(anyBoolean(),anyLong())).thenReturn(ticket);
        when(store.tryAcquireQueued(anyInt(),anyLong(),anyBoolean(),eq(ticket))).thenAnswer(call->{
            Thread.currentThread().interrupt();return new AiCallPermitStore.Admission(null,"queued",100);
        });
        try {
            assertThatThrownBy(()->new AiCallConcurrency(store,new SimpleMeterRegistry(),2).acquire(Duration.ofSeconds(1)))
                    .isInstanceOf(RemoteCallGuard.RemoteCallRejectedException.class);
            verify(store).cancelWaiter(ticket);
        } finally { Thread.interrupted(); }
    }
}
