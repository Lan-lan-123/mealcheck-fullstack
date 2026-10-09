package com.example.mealcheck.service;

import com.example.mealcheck.entity.WeeklyReportCoordinationRun;
import com.example.mealcheck.repository.WeeklyReportCoordinationRunRepository;
import com.example.mealcheck.repository.WeeklyReportCoordinationStepRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportCoordinationRecorderTest {

    @Test
    void recordsStepAndDegradedRunMetricsWithoutHighCardinalityTags() {
        WeeklyReportCoordinationRunRepository runRepository = mock(WeeklyReportCoordinationRunRepository.class);
        WeeklyReportCoordinationStepRepository stepRepository = mock(WeeklyReportCoordinationStepRepository.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportCoordinationRecorder recorder = new WeeklyReportCoordinationRecorder(
                runRepository, stepRepository, observability);

        WeeklyReportCoordinationRecorder.StepHandle step =
                new WeeklyReportCoordinationRecorder.StepHandle(
                        8L, "plan_agent", "AGENT", 1, System.nanoTime());
        recorder.completeStep(step, "FALLBACK", false, true);

        WeeklyReportCoordinationRun run = new WeeklyReportCoordinationRun();
        run.setRunId("run-1");
        run.setStartedAt(LocalDateTime.now().minusSeconds(1));
        when(runRepository.findByRunId("run-1")).thenReturn(Optional.of(run));
        when(stepRepository.countByRunId("run-1")).thenReturn(4L);
        when(stepRepository.countByRunIdAndStatus("run-1", "DEGRADED")).thenReturn(1L);
        when(stepRepository.countByRunIdAndStatus("run-1", "FAILED")).thenReturn(0L);

        recorder.completeRun("run-1", "MULTI_AGENT_PARTIAL_FALLBACK", "APPROVED_RULES", 1);

        verify(stepRepository).finish(eq(8L), eq("DEGRADED"), eq("FALLBACK"), eq(false),
                eq(true), anyLong(), eq(null), any(LocalDateTime.class));
        verify(runRepository).finish(eq("run-1"), eq("DEGRADED"),
                eq("MULTI_AGENT_PARTIAL_FALLBACK"), eq("APPROVED_RULES"), eq(0), eq(4), eq(1), eq(0),
                anyLong(), eq(null), any(LocalDateTime.class));
        verify(observability).recordWeeklyReportCoordinationStep(
                anyLong(), eq("plan_agent"), eq("AGENT"), eq("DEGRADED"), eq(true));
        verify(observability).recordWeeklyReportCoordinationRun(
                anyLong(), eq("DEGRADED"), eq("MULTI_AGENT_PARTIAL_FALLBACK"));
        verify(observability).recordWeeklyReportCoordinationRevisions(0);
    }

    @Test
    void recoversStaleRunAndInterruptsItsRunningSteps() {
        WeeklyReportCoordinationRunRepository runRepository = mock(WeeklyReportCoordinationRunRepository.class);
        WeeklyReportCoordinationStepRepository stepRepository = mock(WeeklyReportCoordinationStepRepository.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportCoordinationRecorder recorder = new WeeklyReportCoordinationRecorder(
                runRepository, stepRepository, observability);
        LocalDateTime now = LocalDateTime.now();
        WeeklyReportCoordinationRun run = new WeeklyReportCoordinationRun();
        run.setRunId("stale-run");
        run.setStartedAt(now.minusMinutes(20));
        run.setUpdatedAt(now.minusMinutes(20));
        when(runRepository.findByStatusAndUpdatedAtBefore(eq("RUNNING"), any(LocalDateTime.class)))
                .thenReturn(List.of(run));
        when(stepRepository.interruptRunning(eq("stale-run"), any(String.class), eq(now)))
                .thenReturn(2);
        when(stepRepository.countByRunId("stale-run")).thenReturn(3L);
        when(stepRepository.countByRunIdAndStatus("stale-run", "DEGRADED")).thenReturn(0L);
        when(stepRepository.countByRunIdAndStatus("stale-run", "FAILED")).thenReturn(0L);
        when(stepRepository.countByRunIdAndStatus("stale-run", "RUNNING")).thenReturn(2L);
        when(runRepository.interruptStale(eq("stale-run"), any(LocalDateTime.class),
                eq(3), eq(0), eq(2), anyLong(), any(String.class), eq(now))).thenReturn(1);
        when(stepRepository.interruptOrphaned(any(String.class), eq(now))).thenReturn(0);

        recorder.recoverStaleRuns(now);

        verify(observability).recordWeeklyReportCoordinationRecovery(1, 2);
        verify(observability).recordWeeklyReportCoordinationRun(
                anyLong(), eq("INTERRUPTED"), eq("INTERRUPTED"));
    }

    @Test
    void redactsSecretsBeforePersistingStepErrors() {
        WeeklyReportCoordinationRunRepository runRepository = mock(WeeklyReportCoordinationRunRepository.class);
        WeeklyReportCoordinationStepRepository stepRepository = mock(WeeklyReportCoordinationStepRepository.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportCoordinationRecorder recorder = new WeeklyReportCoordinationRecorder(
                runRepository, stepRepository, observability);
        WeeklyReportCoordinationRecorder.StepHandle step =
                new WeeklyReportCoordinationRecorder.StepHandle(
                        9L, "review_agent", "AGENT", 1, System.nanoTime());

        recorder.degradeStep(step, "FALLBACK", false,
                new IllegalStateException("Bearer secret.token api_key=super-secret sk-demo12345678"));

        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(stepRepository).finish(eq(9L), eq("DEGRADED"), eq("FALLBACK"), eq(false),
                eq(true), anyLong(), errorMessage.capture(), any(LocalDateTime.class));
        org.assertj.core.api.Assertions.assertThat(errorMessage.getValue())
                .doesNotContain("secret.token", "super-secret", "sk-demo12345678")
                .contains("[REDACTED]");
    }
}
