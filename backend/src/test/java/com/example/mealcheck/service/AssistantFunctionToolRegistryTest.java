package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.entity.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class AssistantFunctionToolRegistryTest {
    private final NextMealService nextMeal = mock(NextMealService.class);
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ragExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        dbExecutor.shutdownNow();
        ragExecutor.shutdownNow();
    }

    @Test
    void exposesOnlyReadOnlyAllowListedTools() {
        AssistantFunctionToolRegistry registry = registry(mock(KnowledgeIndexService.class));

        assertThat(registry.specifications())
                .extracting(specification -> specification.name())
                .containsExactly(
                        "search_diet_knowledge",
                        "get_my_recent_meals",
                        "get_my_weekly_report",
                        "get_my_current_goal", "recommend_next_meal");
    }

    @Test
    void clampsKnowledgeLimitAndReturnsReferences() {
        KnowledgeIndexService knowledge = mock(KnowledgeIndexService.class);
        KnowledgeSnippet snippet = new KnowledgeSnippet(3L, "title", "content", 0.8);
        when(knowledge.search("protein", 5)).thenReturn(List.of(snippet));
        AssistantFunctionToolRegistry registry = registry(knowledge);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("search_diet_knowledge")
                .arguments("{\"query\":\"protein\",\"limit\":99}")
                .build();

        AssistantFunctionToolRuntime.ToolResult result = registry.execute(
                user(), request, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

        assertThat(result.content()).contains("title").contains("content");
        assertThat(result.references()).containsExactly(snippet);
        verify(knowledge).search("protein", 5);
        verify(knowledge).recordHits(List.of(3L));
    }

    @Test
    void rejectsUnknownToolBeforeExecution() {
        AssistantFunctionToolRegistry registry = registry(mock(KnowledgeIndexService.class));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("delete_all_meals")
                .arguments("{}")
                .build();

        assertThatThrownBy(() -> registry.execute(
                user(), request, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    private AssistantFunctionToolRegistry registry(KnowledgeIndexService knowledge) {
        return new AssistantFunctionToolRegistry(
                knowledge,
                mock(MealAnalysisService.class),
                mock(WeeklyReportService.class),
                mock(UserGoalService.class),
                nextMeal,
                new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                dbExecutor,
                ragExecutor,
                new AppProperties(),
                mock(ApplicationObservability.class));
    }

    @Test
    void planningToolUsesAuthenticatedOwnerAndRejectsInvalidOrExtraArguments() {
        var registry = registry(mock(KnowledgeIndexService.class));
        var constraints = new com.example.mealcheck.dto.MealPlanningDtos.NextMealRequest("DINNER", "HOME", 25, 20);
        when(nextMeal.recommend(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(constraints)))
                .thenReturn(new com.example.mealcheck.dto.MealPlanningDtos.Recommendations(java.time.LocalDate.of(2026, 10, 24),
                        constraints, "balanced", "已应用偏好", 0, "暂无记录", "参考费用", "准备时间", List.of(), "没有符合条件的方案"));
        String valid = "{\"mealType\":\"DINNER\",\"scene\":\"HOME\",\"budget\":25,\"availableMinutes\":20}";
        var args = List.of(valid.replace("25", "0"), valid.replace("25", "25.5"), valid.replace("20}", "121}"),
                valid.replace("HOME", "UNKNOWN"), valid.replace("20}", "20,\"userId\":999}"));
        for (String bad : args) assertThatThrownBy(() -> registry.execute(user(), planning(bad), System.nanoTime() + TimeUnit.SECONDS.toNanos(3)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(nextMeal);
        var result = registry.execute(user(), planning(valid), System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
        assertThat(result.content()).contains("没有符合条件", "2026-10-24");
        verify(nextMeal).recommend(org.mockito.ArgumentMatchers.argThat(principal -> principal.getId().equals(1L)), org.mockito.ArgumentMatchers.eq(constraints));
    }

    private ToolExecutionRequest planning(String args) {
        return ToolExecutionRequest.builder().id("planning").name("recommend_next_meal").arguments(args).build();
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash("hash");
        return user;
    }
}
