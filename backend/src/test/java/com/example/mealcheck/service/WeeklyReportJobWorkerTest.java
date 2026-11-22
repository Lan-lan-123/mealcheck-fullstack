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
    @Test void expiredGenerationCannotPublishAndMarksTheSameJobFailed() {
        var jobs = mock(WeeklyReportJobService.class);
        var reports = mock(WeeklyReportService.class);
        var completion = mock(WeeklyReportJobCompletionService.class);
        var observability = mock(ApplicationObservability.class);
        var job = job(13L, 23L, 7, 1);
        when(jobs.claimBatch(1)).thenReturn(List.of(job));
        when(jobs.beginExecution(13L, 1, job.getExecutionToken())).thenReturn(true);
        when(reports.generateForJob(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            org.assertj.core.api.Assertions.assertThat(AiCallContext.capture().userId()).isEqualTo(23L);
            Thread.sleep(1100);
            return new WeeklyReportResponse();
        });
        var worker = new WeeklyReportJobWorker(jobs, reports, Runnable::run, observability, completion);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "operationTimeoutSeconds", 1);
        worker.poll();
        org.mockito.Mockito.verifyNoInteractions(completion);
        String executionToken = job.getExecutionToken();
        verify(jobs).markFailed(org.mockito.ArgumentMatchers.eq(13L), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(executionToken),
                org.mockito.ArgumentMatchers.isA(RemoteCallGuard.RemoteCallDeadlineException.class));
        org.assertj.core.api.Assertions.assertThat(AiCallContext.capture()).isNull();
    }

    @Test
    void completesClaimedJobOnConfiguredExecutor() {
        WeeklyReportJobService jobService = mock(WeeklyReportJobService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportJobCompletionService completion = mock(WeeklyReportJobCompletionService.class);
        WeeklyReportJob job = job(11L, 21L, 7, 1);
        when(jobService.claimBatch(1)).thenReturn(List.of(job));
        when(jobService.beginExecution(11L, 1, job.getExecutionToken())).thenReturn(true);
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
    void rejectedDispatchDoesNotClaimAJobOrSpendAnAttempt() {
        WeeklyReportJobService jobService = mock(WeeklyReportJobService.class);
        WeeklyReportService reportService = mock(WeeklyReportService.class);
        ApplicationObservability observability = mock(ApplicationObservability.class);
        WeeklyReportJobCompletionService completion = mock(WeeklyReportJobCompletionService.class);
        RejectedExecutionException rejection = new RejectedExecutionException("saturated");
        Executor rejectingExecutor = task -> { throw rejection; };
        WeeklyReportJobWorker worker = new WeeklyReportJobWorker(
                jobService, reportService, rejectingExecutor, observability, completion);

        worker.poll();

        org.mockito.Mockito.verifyNoInteractions(jobService);
        verify(reportService, org.mockito.Mockito.never()).generateForJob(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(observability).recordWeeklyReportJob("rejected");
    }

    @Test void resumedDispatchKeepsTheOriginalReportPeriod() {
        var jobs = mock(WeeklyReportJobService.class);
        var reports = mock(WeeklyReportService.class);
        var job = job(14L, 24L, 7, 1);
        var week = java.time.LocalDate.of(2026, 9, 21);
        when(job.getReportWeek()).thenReturn(week);
        when(jobs.claimBatch(1)).thenReturn(List.of(job));
        when(jobs.beginExecution(14L, 1, job.getExecutionToken())).thenReturn(true);
        when(reports.generateForJob(24L, 7, 14L, week)).thenAnswer(call -> {
            org.assertj.core.api.Assertions.assertThat(AiCallContext.capture().background()).isTrue();
            return new WeeklyReportResponse();
        });
        new WeeklyReportJobWorker(jobs, reports, Runnable::run, mock(ApplicationObservability.class),
                mock(WeeklyReportJobCompletionService.class)).poll();
        verify(reports).generateForJob(24L, 7, 14L, week);
    }

    @Test void waitingDispatcherDoesNotStartALeaseAndRepeatedPollsStayBounded() {
        var jobs = mock(WeeklyReportJobService.class);
        var reports = mock(WeeklyReportService.class);
        var pending = new java.util.ArrayList<Runnable>();
        var worker = new WeeklyReportJobWorker(jobs, reports, pending::add,
                mock(ApplicationObservability.class), mock(WeeklyReportJobCompletionService.class));
        worker.poll(); worker.poll(); worker.poll();
        org.assertj.core.api.Assertions.assertThat(pending).hasSize(1);
        org.mockito.Mockito.verifyNoInteractions(jobs, reports);
        pending.remove(0).run();
        verify(jobs).claimBatch(1);
        worker.poll();
        org.assertj.core.api.Assertions.assertThat(pending).hasSize(1);
    }

    @Test void expiredOrReplacedClaimNeverCallsTheModel() {
        var jobs = mock(WeeklyReportJobService.class);
        var reports = mock(WeeklyReportService.class);
        var completion = mock(WeeklyReportJobCompletionService.class);
        var job = job(15L, 25L, 7, 1);
        when(jobs.claimBatch(1)).thenReturn(List.of(job));
        when(jobs.beginExecution(15L, 1, job.getExecutionToken())).thenReturn(false);
        new WeeklyReportJobWorker(jobs, reports, Runnable::run,
                mock(ApplicationObservability.class), completion).poll();
        org.mockito.Mockito.verifyNoInteractions(reports, completion);
        verify(jobs, org.mockito.Mockito.never()).markFailed(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void failedClaimReleasesTheDispatcherSlot() {
        var jobs = mock(WeeklyReportJobService.class);
        when(jobs.claimBatch(1)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(List.of());
        var worker = new WeeklyReportJobWorker(jobs, mock(WeeklyReportService.class), Runnable::run,
                mock(ApplicationObservability.class), mock(WeeklyReportJobCompletionService.class));
        worker.poll(); worker.poll();
        verify(jobs, org.mockito.Mockito.times(2)).claimBatch(1);
    }

    @Test void directHandoffUsesMaxWorkersWithoutClaimingMoreJobsWhileBusy() throws Exception {
        var properties = new com.example.mealcheck.config.AppProperties();
        properties.getWeeklyReport().setExecutorCorePoolSize(1);
        properties.getWeeklyReport().setExecutorMaxPoolSize(2);
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var executor = (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor)
                new com.example.mealcheck.config.AppConfig().weeklyReportExecutor(properties, metrics);
        var jobs = mock(WeeklyReportJobService.class);
        var reports = mock(WeeklyReportService.class);
        var started = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = job(16L, 26L, 7, 1);
        var second = job(17L, 27L, 7, 1);
        when(jobs.claimBatch(1)).thenReturn(List.of(first), List.of(second));
        when(jobs.beginExecution(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(reports.generateForJob(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            started.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return new WeeklyReportResponse();
        });
        try {
            var worker = new WeeklyReportJobWorker(jobs, reports, executor,
                    mock(ApplicationObservability.class), mock(WeeklyReportJobCompletionService.class));
            worker.poll();
            org.assertj.core.api.Assertions.assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            worker.poll(); worker.poll();
            verify(jobs, org.mockito.Mockito.times(2)).claimBatch(1);
            org.assertj.core.api.Assertions.assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        } finally { release.countDown(); executor.shutdown(); metrics.close(); }
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
