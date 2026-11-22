package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeeklyReportJobCompletionServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void preservesOriginalPeriodWhenDispatchResumesOnAnotherDay(boolean hasReportPeriod) throws Exception {
        var jobs = mock(WeeklyReportJobRepository.class);
        var snapshots = mock(WeeklyReportSnapshotRepository.class);
        var users = mock(UserAccountRepository.class);
        var job = new com.example.mealcheck.entity.WeeklyReportJob();
        job.setUserId(7L); job.setDays(7);
        var createdDay = java.time.LocalDate.of(2026, 10, 22);
        var originalEnd = java.time.LocalDate.of(2026, 10, 19);
        org.springframework.test.util.ReflectionTestUtils.setField(job, "createdAt", com.example.mealcheck.util.AppTime.startOfDay(createdDay));
        job.setReportWeek(hasReportPeriod ? originalEnd : null);
        var expectedEnd = hasReportPeriod ? originalEnd : createdDay;
        var user = new com.example.mealcheck.entity.UserAccount(); user.setId(7L);
        when(jobs.findById(1L)).thenReturn(java.util.Optional.of(job));
        when(users.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(user));
        when(users.getReferenceById(7L)).thenReturn(user);
        when(jobs.markSucceeded(eq(1L), eq(1), eq("token"), any())).thenReturn(1);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var service = new WeeklyReportJobCompletionService(jobs, snapshots, users, mapper, mock(TaskFailureRecorder.class));
        var report = new WeeklyReportResponse();
        report.setPeriodEnd(expectedEnd); report.setPeriodStart(expectedEnd.minusDays(6));
        report.setReportText("Period ending " + expectedEnd);
        assertThat(service.complete(1L, 1, "token", report)).isTrue();
        var saved = org.mockito.ArgumentCaptor.forClass(com.example.mealcheck.entity.WeeklyReportSnapshot.class);
        verify(snapshots).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPeriodEnd()).isEqualTo(expectedEnd);
        assertThat(saved.getValue().getPeriodStart()).isEqualTo(expectedEnd.minusDays(6));
        var stored = mapper.readValue(saved.getValue().getReportJson(), WeeklyReportResponse.class);
        assertThat(stored.getPeriodEnd()).isEqualTo(expectedEnd);
        assertThat(stored.getReportText()).isEqualTo("Period ending " + expectedEnd);
    }

    @Test
    void staleOrDuplicateAttemptCannotPublishAnySnapshot() {
        var jobs = mock(WeeklyReportJobRepository.class);
        var snapshots = mock(WeeklyReportSnapshotRepository.class);
        var users = mock(UserAccountRepository.class);
        when(jobs.markSucceeded(eq(1L), eq(1), anyString(), any())).thenReturn(0);
        var service = new WeeklyReportJobCompletionService(jobs, snapshots, users, new ObjectMapper(), mock(TaskFailureRecorder.class));
        assertThat(service.complete(1L, 1, "token", new WeeklyReportResponse())).isFalse();
        verifyNoInteractions(snapshots, users);
    }
}
