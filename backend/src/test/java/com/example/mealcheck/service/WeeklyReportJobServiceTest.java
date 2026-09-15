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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportJobServiceTest {

    @Test
    void enqueuesEachUserWithAnIdempotencyKey() {
        WeeklyReportJobRepository repository = mock(WeeklyReportJobRepository.class);
        when(repository.enqueue(anyLong(), any(), anyInt(), any())).thenReturn(1, 0);
        WeeklyReportJobService service = new WeeklyReportJobService(repository, new AppProperties());
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
        WeeklyReportJobService service = new WeeklyReportJobService(repository, properties);

        List<WeeklyReportJob> claimed = service.claimBatch();

        assertThat(claimed).containsExactly(job);
        assertThat(job.getStatus()).isEqualTo(WeeklyReportJob.RUNNING);
        assertThat(job.getAttempts()).isEqualTo(1);
        verify(repository).recoverStale(any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void failedJobRetriesUntilMaximumAttemptsThenMovesToDeadState() {
        WeeklyReportJobRepository repository = mock(WeeklyReportJobRepository.class);
        when(repository.markFailed(any(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        AppProperties properties = new AppProperties();
        properties.getWeeklyReport().setMaxAttempts(3);
        WeeklyReportJobService service = new WeeklyReportJobService(repository, properties);

        assertThat(service.markFailed(9L, 2, new IllegalStateException("temporary"))).isTrue();
        assertThat(service.markFailed(9L, 3, new IllegalStateException("permanent"))).isTrue();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.times(2)).markFailed(
                eq(9L), anyInt(), status.capture(), any(), any(), any());
        assertThat(status.getAllValues()).containsExactly(WeeklyReportJob.RETRY, WeeklyReportJob.DEAD);
    }
}
