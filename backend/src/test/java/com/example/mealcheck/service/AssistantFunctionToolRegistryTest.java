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

class AssistantFunctionToolRegistryTest {
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
                        "get_my_current_goal");
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

        AssistantFunctionToolRegistry.ToolResult result = registry.execute(
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
                new ObjectMapper(),
                dbExecutor,
                ragExecutor,
                new AppProperties(),
                mock(ApplicationObservability.class));
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash("hash");
        return user;
    }
}
