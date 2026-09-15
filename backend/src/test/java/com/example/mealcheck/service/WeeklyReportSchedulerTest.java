package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportSchedulerTest {

    @Test
    void dispatchesUsersInPages() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        WeeklyReportJobService jobService = mock(WeeklyReportJobService.class);
        AppProperties properties = new AppProperties();
        properties.getWeeklyReport().setBatchSize(10);
        WeeklyReportScheduler scheduler = new WeeklyReportScheduler(repository, jobService, properties);

        UserAccount first = user(1L);
        UserAccount second = user(2L);
        UserAccount third = user(3L);
        when(repository.findAll(any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(first, second), PageRequest.of(0, 10), 11),
                new PageImpl<>(List.of(third), PageRequest.of(1, 10), 11)
        );

        scheduler.generateWeeklySnapshots();

        verify(jobService).enqueue(eq(List.of(1L, 2L)), eq(7), any());
        verify(jobService).enqueue(eq(List.of(3L)), eq(7), any());
    }

    private UserAccount user(Long id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        return user;
    }
}
