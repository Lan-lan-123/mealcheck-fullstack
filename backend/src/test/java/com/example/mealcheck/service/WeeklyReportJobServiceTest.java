package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.WeeklyReportJob;
import com.example.mealcheck.repository.WeeklyReportJobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportJobServiceTest {
    @Test void admissionFailuresWaitWithoutSpendingAttemptsAndConfigurationErrorsStopAutomaticRetry() {
        var repository=mock(WeeklyReportJobRepository.class);
        var users=mock(com.example.mealcheck.repository.UserAccountRepository.class);
        var failure=mock(TaskFailureRecorder.class);var job=new WeeklyReportJob();job.setUserId(7L);
        when(repository.findById(9L)).thenReturn(java.util.Optional.of(job));
        when(users.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(new com.example.mealcheck.entity.UserAccount()));
        when(repository.deferAdmission(any(),anyInt(),any(),any(),any(),any())).thenReturn(1);
        when(repository.markFailed(any(),anyInt(),any(),any(),any(),any(),any())).thenReturn(1);
        var service=new WeeklyReportJobService(repository,new AppProperties(),failure,users);
        var before=com.example.mealcheck.util.AppTime.now();
        assertThat(service.markFailed(9L,3,"token",new AiBudgetService.BudgetExceededException(100,3600))).isTrue();
        verify(repository).deferAdmission(eq(9L),eq(3),eq("token"),eq("AI_BUDGET_EXCEEDED"),argThat(next->!next.isBefore(before.plusSeconds(3600))),any());
        service.markFailed(9L,1,"token",new RemoteCallGuard.RetryableRemoteCallException(429,java.time.Duration.ofSeconds(500)));
        verify(repository).markFailed(eq(9L),eq(1),eq("token"),eq(WeeklyReportJob.RETRY),any(),argThat(next->!next.isBefore(before.plusSeconds(500))),any());
        service.markFailed(9L,2,"token",new RemoteCallGuard.RemoteCallDeadlineException("provider delay",java.time.Duration.ofSeconds(1000)));
        verify(repository).markFailed(eq(9L),eq(2),eq("token"),eq(WeeklyReportJob.RETRY),any(),argThat(next->!next.isBefore(before.plusSeconds(1000))),any());
        service.markFailed(9L,1,"token",new RemoteCallGuard.NonRetryableRemoteCallException("invalid key"));
        verify(repository).markFailed(eq(9L),eq(1),eq("token"),eq(WeeklyReportJob.DEAD),eq("REMOTE_CONFIGURATION"),any(),any());
    }

    @Test
    void enqueuesEachUserWithAnIdempotencyKey() {
        WeeklyReportJobRepository repository = mock(WeeklyReportJobRepository.class);
        when(repository.enqueue(anyLong(), any(), anyInt(), any())).thenReturn(1, 0);
        WeeklyReportJobService service = new WeeklyReportJobService(repository, new AppProperties(), mock(TaskFailureRecorder.class), mock(com.example.mealcheck.repository.UserAccountRepository.class));
        LocalDate reportWeek = LocalDate.of(2026, 8, 24);

        int inserted = service.enqueue(List.of(1L, 2L), 7, reportWeek);

        assertThat(inserted).isEqualTo(1);
        verify(repository).enqueue(eq(1L), eq(reportWeek), eq(7), any(LocalDateTime.class));
        verify(repository).enqueue(eq(2L), eq(reportWeek), eq(7), any(LocalDateTime.class));
    }

    @Test
    void claimsJobsAndAdvancesAttemptBeforeDispatch() {
        WeeklyReportJobRepository repository = mock(WeeklyReportJobRepository.class);
        AppProperties properties = new AppProperties();
        properties.getWeeklyReport().setWorkerBatchSize(3);
        WeeklyReportJob job = new WeeklyReportJob();
        when(repository.findClaimable(any(), eq(3))).thenReturn(List.of(job));
        WeeklyReportJobService service = new WeeklyReportJobService(repository, properties, mock(TaskFailureRecorder.class), mock(com.example.mealcheck.repository.UserAccountRepository.class));

        List<WeeklyReportJob> claimed = service.claimBatch();

        assertThat(claimed).containsExactly(job);
        assertThat(job.getStatus()).isEqualTo(WeeklyReportJob.RUNNING);
        assertThat(job.getAttempts()).isEqualTo(1);
        verify(repository).recoverStale(any(LocalDateTime.class), any(LocalDateTime.class), eq(3));
    }

    @Test
    void failedJobRetriesUntilMaximumAttemptsThenMovesToDeadState() {
        WeeklyReportJobRepository repository = mock(WeeklyReportJobRepository.class);
        when(repository.markFailed(any(), anyInt(), any(), any(), any(), any(), any())).thenReturn(1);
        AppProperties properties = new AppProperties();
        properties.getWeeklyReport().setMaxAttempts(3);
        var users = mock(com.example.mealcheck.repository.UserAccountRepository.class);
        var job = new WeeklyReportJob(); job.setUserId(1L);
        when(repository.findById(9L)).thenReturn(java.util.Optional.of(job));
        when(users.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(new com.example.mealcheck.entity.UserAccount()));
        WeeklyReportJobService service = new WeeklyReportJobService(repository, properties, mock(TaskFailureRecorder.class), users);

        assertThat(service.markFailed(9L, 2, "token", new IllegalStateException("temporary"))).isTrue();
        assertThat(service.markFailed(9L, 3, "token", new IllegalStateException("permanent"))).isTrue();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(2)).markFailed(
                eq(9L), anyInt(), any(), status.capture(), any(), any(), any());
        assertThat(status.getAllValues()).containsExactly(WeeklyReportJob.RETRY, WeeklyReportJob.DEAD);
    }
}
