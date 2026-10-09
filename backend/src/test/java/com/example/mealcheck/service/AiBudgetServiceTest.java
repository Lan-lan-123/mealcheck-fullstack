package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AiBudgetServiceTest {
    final AppProperties properties=new AppProperties();
    final AiBudgetStore store=mock(AiBudgetStore.class);
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    final AiBudgetService budgets=new AiBudgetService(properties,store,metrics,mock(BackgroundTaskDispatcher.class));
    @Test void propagatedUserAndExplicitSystemOperationsUseSeparateQuotas() {
        try (var scope=AiCallContext.open(7L,Duration.ofSeconds(10))) {
            budgets.reserve(BigDecimal.ZERO);
            try (var system=AiCallContext.systemOperation(Duration.ofSeconds(10))) { budgets.reserve(BigDecimal.ZERO); }
            assertThat(AiCallContext.capture().userId()).isEqualTo(7L);
        }
        verify(store).reserve(eq("user:7"),eq(7L),any(),eq(100),eq(BigDecimal.ZERO),eq(new BigDecimal("0.020000")),anyLong());
        verify(store).reserve(eq("system"),isNull(),any(),eq(5000),eq(BigDecimal.ZERO),eq(new BigDecimal("0.020000")),anyLong());
        assertThat(AiCallContext.capture()).isNull();
    }
    @Test void deferredSettlementKeepsPersistedHoldForRecovery() {
        var reservation=new AiBudgetStore.Reservation(java.util.UUID.randomUUID(),"user:7",LocalDate.now(),new BigDecimal("0.02"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(store).settle(reservation,null);
        budgets.settle(reservation,null);
        assertThat(metrics.get("mealcheck.ai.budget.settlement.deferred").counter().count()).isEqualTo(1);
        verify(store,never()).recover(any());
    }
    @Test void budgetStoreFailureRejectsAdmissionInsteadOfRunningWithoutAccounting() {
        when(store.reserve(anyString(),any(),any(),anyInt(),any(),any(),anyLong())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->budgets.reserve(BigDecimal.ZERO)).isInstanceOf(AiBudgetService.BudgetUnavailableException.class);
    }
    @Test void disabledBudgetsDoNotAccessDatabaseAndInvalidConfigurationFailsStartup() {
        properties.getAiBudget().setEnabled(false);
        assertThat(budgets.reserve(BigDecimal.ZERO)).isNull();budgets.settle(null,null);verifyNoInteractions(store);
        properties.getAiBudget().setOperationTimeoutSeconds(601);
        assertThatThrownBy(budgets::validateConfiguration).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void recoveryHealthAndBacklogAreVisibleWithoutUserLabels() {
        var dispatcher=mock(BackgroundTaskDispatcher.class);
        doAnswer(call->{ call.<Runnable>getArgument(1).run();return true; }).when(dispatcher).submit(anyString(),any());
        var service=new AiBudgetService(properties,store,metrics,dispatcher);
        when(store.stale()).thenReturn(java.util.List.of());
        when(store.recoveryStats()).thenReturn(java.util.Map.of("unsettled",3L,"stale",2L,"oldest_seconds",700));
        service.tick();assertThat(service.monitoring()).containsEntry("monitorHealthy",true).containsEntry("stale",2L);
        when(store.stale()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(service::tick).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.monitoring()).containsEntry("monitorHealthy",false).doesNotContainKey("stale");
    }
}
