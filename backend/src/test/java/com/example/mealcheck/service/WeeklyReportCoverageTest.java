package com.example.mealcheck.service;

import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.weeklyagent.WeeklyReportMultiAgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeeklyReportCoverageTest {
    @Test
    void countsMealsSeparatelyFromFoodItemsAndUsesFixedCalendarBoundaries() {
        var users = mock(UserAccountRepository.class);
        var meals = mock(MealAnalysisService.class);
        var goals = mock(UserGoalService.class);
        var graph = mock(WeeklyReportMultiAgentOrchestrator.class);
        var service = new WeeklyReportService(users, meals, new ObjectMapper(),
                mock(WeeklyReportSnapshotRepository.class), goals, graph);
        var user = new UserAccount(); user.setId(7L); user.setUsername("test"); user.setPasswordHash("hash");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        var first = new MealRecord(); first.setScore(90);
        first.setCategoryCountsJson("{\"vegetable\":2,\"protein\":2}"); first.setRiskTagsJson("[]");
        var second = new MealRecord(); second.setScore(60);
        second.setCategoryCountsJson("{\"staple\":1}"); second.setRiskTagsJson("[]");
        when(meals.listPeriod(eq(user), any(), any())).thenReturn(List.of(first, second));
        when(goals.effectiveGoal(user, "current")).thenReturn("balanced");
        var report = service.generate(new UserPrincipal(user), 7);
        assertThat(report.getCategoryTotals()).containsEntry("vegetable", 2);
        assertThat(report.getCategoryMealCounts()).containsEntry("vegetable", 1).containsEntry("protein", 1);
        assertThat(report.getTotalMeals()).isEqualTo(2);
        assertThat(report.getHighlights()).contains("蔬菜覆盖率还可以继续提高。", "部分餐次缺少稳定蛋白来源。");
        verifyNoInteractions(graph);

        var end = LocalDate.of(2026, 9, 20);
        when(graph.generate(eq(user), any(), eq(11L))).thenAnswer(call -> call.getArgument(1));
        var generated = service.generateForJob(7L, 7, 11L, end);
        assertThat(generated.getPeriodEnd()).isEqualTo(end);
        assertThat(generated.getPeriodStart()).isEqualTo(end.minusDays(6));
        verify(meals).listPeriod(user, end.minusDays(6), end);
    }
}
