package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class AssistantEvaluationToolRuntimeFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ragKnowledgeIsOnlyReturnedByAnExplicitToolCallAndHonorsLimit() throws Exception {
        var production = mock(AssistantFunctionToolRegistry.class);
        var context = objectMapper.readTree("""
                {"ragToolEvaluation":true,"ragKnowledgeFixtures":[
                  {"id":1,"title":"早餐","content":"受控知识正文"},
                  {"id":2,"title":"蛋白质","content":"另一条知识"}]}
                """);
        var runtime = new AssistantEvaluationToolRuntimeFactory(production, objectMapper).create(context, "不得直接返回的上下文");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        var result = runtime.execute(null, request("search_diet_knowledge", "{\"query\":\"早餐\",\"limit\":1}"), deadline);
        assertThat(result.status()).isEqualTo("SUCCESS");
        var content = objectMapper.readTree(result.content());
        assertThat(content.path("count").asInt()).isEqualTo(1);
        assertThat(content.path("results").size()).isEqualTo(1);
        assertThat(result.content()).contains("受控知识正文").doesNotContain("不得直接返回的上下文");
        assertThatThrownBy(() -> runtime.execute(null,
                request("search_diet_knowledge", "{\"query\":\"早餐\",\"limit\":1.5}"), deadline))
                .isInstanceOf(IllegalArgumentException.class);
        verify(production, never()).execute(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void reusesProductionSpecificationsButExecutesOnlySyntheticData() throws Exception {
        AssistantFunctionToolRegistry production = mock(AssistantFunctionToolRegistry.class);
        List<ToolSpecification> specifications = List.of(mock(ToolSpecification.class));
        when(production.specifications()).thenReturn(specifications);
        JsonNode context = objectMapper.readTree("""
                {"currentGoal":"fat_loss","recentMeals":[{"foods":["米饭"]}],
                 "toolState":"AVAILABLE"}
                """);
        AssistantFunctionToolRuntime runtime = new AssistantEvaluationToolRuntimeFactory(
                production, objectMapper).create(context, "knowledge");

        assertThat(runtime.specifications()).isSameAs(specifications);
        AssistantFunctionToolRuntime.ToolResult result = runtime.execute(
                null,
                request("get_my_current_goal", "{}"),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.content()).contains("fat_loss");
        verify(production, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void exposesUnavailableStateAndValidatesArguments() throws Exception {
        AssistantFunctionToolRegistry production = mock(AssistantFunctionToolRegistry.class);
        JsonNode context = objectMapper.readTree("""
                {"toolState":"DIET_KNOWLEDGE_UNAVAILABLE"}
                """);
        AssistantFunctionToolRuntime runtime = new AssistantEvaluationToolRuntimeFactory(
                production, objectMapper).create(context, "ignored");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

        AssistantFunctionToolRuntime.ToolResult unavailable = runtime.execute(
                null, request("search_diet_knowledge", "{\"query\":\"早餐\"}"), deadline);

        assertThat(unavailable.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.content()).contains("unavailable");
        assertThatThrownBy(() -> runtime.execute(
                null, request("get_my_recent_meals", "{\"userId\":99}"), deadline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tool argument");
    }

    private ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(name)
                .arguments(arguments)
                .build();
    }
}
