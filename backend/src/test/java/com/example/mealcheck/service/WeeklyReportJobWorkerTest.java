package com.example.mealcheck.service;

import com.example.mealcheck.entity.WeeklyReportJob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportJobWorkerTest {

    @Test
    void completesClaimedJobOnConfiguredExecutor() {
        WeeklyReportJobService jobService = mock(WeeklyReportJobService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportJob job = job(11L, 21L, 7, 1);
        when(jobService.claimBatch()).thenReturn(List.of(job));
        when(jobService.markSucceeded(11L, 1)).thenReturn(true);
        Executor directExecutor = Runnable::run;
        WeeklyReportJobWorker worker = new WeeklyReportJobWorker(
                jobService, reportService, directExecutor, observability);

        worker.poll();

        verify(reportService).generateAndStore(21L, 7);
        verify(jobService).markSucceeded(11L, 1);
        verify(observability).recordWeeklyReportJob("success");
    }

    @Test
    void rejectedDispatchReturnsJobToRetryFlow() {
        WeeklyReportJobService jobService = mock(WeeklyReportJobService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportJob job = job(12L, 22L, 7, 1);
        when(jobService.claimBatch()).thenReturn(List.of(job));
        RejectedExecutionException rejection = new RejectedExecutionException("saturated");
        Executor rejectingExecutor = task -> { throw rejection; };
        WeeklyReportJobWorker worker = new WeeklyReportJobWorker(
                jobService, reportService, rejectingExecutor, observability);

        worker.poll();

        verify(jobService).markFailed(12L, 1, rejection);
        verify(observability).recordWeeklyReportJob("rejected");
    }

    private WeeklyReportJob job(Long id, Long userId, int days, int attempts) {
        WeeklyReportJob job = mock(WeeklyReportJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getUserId()).thenReturn(userId);
        when(job.getDays()).thenReturn(days);
        when(job.getAttempts()).thenReturn(attempts);
        return job;
    }
}
