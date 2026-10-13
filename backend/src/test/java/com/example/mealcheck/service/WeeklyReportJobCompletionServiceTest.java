package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeeklyReportJobCompletionServiceTest {
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
