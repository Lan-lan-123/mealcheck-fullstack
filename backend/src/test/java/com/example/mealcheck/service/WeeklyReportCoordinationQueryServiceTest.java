package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportCoordinationPageResponse;
import com.example.mealcheck.entity.WeeklyReportCoordinationRun;
import com.example.mealcheck.repository.WeeklyReportCoordinationRunRepository;
import com.example.mealcheck.repository.WeeklyReportCoordinationStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyReportCoordinationQueryServiceTest {

    @Test
    void returnsFilteredPageAndClampsPageSize() {
        WeeklyReportCoordinationRunRepository runRepository = mock(WeeklyReportCoordinationRunRepository.class);
        WeeklyReportCoordinationStepRepository stepRepository = mock(WeeklyReportCoordinationStepRepository.class);
        WeeklyReportCoordinationQueryService service = new WeeklyReportCoordinationQueryService(
                runRepository, stepRepository);
        WeeklyReportCoordinationRun run = mock(WeeklyReportCoordinationRun.class);
        when(run.getRunId()).thenReturn("run-1");
        when(run.getStatus()).thenReturn("DEGRADED");
        when(run.getStartedAt()).thenReturn(LocalDateTime.now());
        when(runRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 100), 1));

        WeeklyReportCoordinationPageResponse response = service.recent(
                -1, 500, "degraded", 9L, 12L);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).extracting(item -> item.runId()).containsExactly("run-1");
    }
}
