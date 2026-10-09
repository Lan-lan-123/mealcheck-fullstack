package com.example.mealcheck.service;

import com.example.mealcheck.entity.WeeklyReportJob;
import com.example.mealcheck.dto.WeeklyReportResponse;
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
        WeeklyReportJobCompletionService completion = mock(WeeklyReportJobCompletionService.class);
        WeeklyReportJob job = job(11L, 21L, 7, 1);
        when(jobService.claimBatch(1)).thenReturn(List.of(job));
        when(completion.complete(org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        WeeklyReportResponse response = new WeeklyReportResponse();
        response.setGenerationMode("MULTI_AGENT");
        when(reportService.generateForJob(21L, 7, 11L, java.time.LocalDate.of(2026, 9, 29))).thenReturn(response);
        Executor directExecutor = Runnable::run;
        WeeklyReportJobWorker worker = new WeeklyReportJobWorker(
                jobService, reportService, directExecutor, observability, completion);

        worker.poll();

        verify(reportService).generateForJob(21L, 7, 11L, java.time.LocalDate.of(2026, 9, 29));
        verify(completion).complete(11L, 1, job.getExecutionToken(), response);
        verify(observability).recordWeeklyReportJob("success");
    }

    @Test
    void rejectedDispatchReturnsJobToRetryFlow() {
        WeeklyReportJobService jobService = mock(WeeklyReportJobService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportJobCompletionService completion = mock(WeeklyReportJobCompletionService.class);
        WeeklyReportJob job = job(12L, 22L, 7, 1);
        when(jobService.claimBatch(1)).thenReturn(List.of(job));
        RejectedExecutionException rejection = new RejectedExecutionException("saturated");
        Executor rejectingExecutor = task -> { throw rejection; };
        WeeklyReportJobWorker worker = new WeeklyReportJobWorker(
                jobService, reportService, rejectingExecutor, observability, completion);

        worker.poll();

        verify(jobService).releaseUndispatched(12L, 1, job.getExecutionToken());
        verify(reportService, org.mockito.Mockito.never()).generateForJob(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(observability).recordWeeklyReportJob("rejected");
    }

    private WeeklyReportJob job(Long id, Long userId, int days, int attempts) {
        WeeklyReportJob job = mock(WeeklyReportJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getUserId()).thenReturn(userId);
        when(job.getDays()).thenReturn(days);
        when(job.getAttempts()).thenReturn(attempts);
        when(job.getExecutionToken()).thenReturn("execution-token");
        when(job.getCreatedAt()).thenReturn(java.time.LocalDateTime.of(2026, 9, 29, 12, 0));
        return job;
    }
}
