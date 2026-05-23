package com.example.mealcheck.service;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.WeeklyReportSnapshotRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyReportServiceTest {

    @Test
    void generateBuildsSuggestionsAndUsesCurrentGoal() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        MealAnalysisService mealAnalysisService = mock(MealAnalysisService.class);
        WeeklyReportSnapshotRepository snapshotRepository = mock(WeeklyReportSnapshotRepository.class);
        UserGoalService userGoalService = mock(UserGoalService.class);
        WeeklyReportService service = new WeeklyReportService(
                userRepository,
                mealAnalysisService,
                new ObjectMapper(),
                snapshotRepository,
                userGoalService
        );

        UserAccount user = new UserAccount();
        user.setId(9L);
        user.setUsername("demo");
        user.setPasswordHash("hash");

        MealRecord record = new MealRecord();
        record.setScore(72);
        record.setCategoryCountsJson("{\"protein\":1}");
        record.setRiskTagsJson("[\"high_oil\"]");

        when(userRepository.findByUsername("demo")).thenReturn(Optional.of(user));
        when(mealAnalysisService.listSince(user, 7)).thenReturn(List.of(record));
        when(userGoalService.effectiveGoal(user, "current")).thenReturn("fat_loss");

        WeeklyReportResponse response = service.generate(new UserPrincipal(user), 7);

        assertThat(response.getGoalType()).isEqualTo("fat_loss");
        assertThat(response.getNextWeekSuggestions()).isNotEmpty();
        assertThat(response.getGeneratedAt()).isNotNull();
    }
}
