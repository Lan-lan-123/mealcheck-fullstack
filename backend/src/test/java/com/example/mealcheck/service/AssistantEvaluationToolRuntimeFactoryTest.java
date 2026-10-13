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

class AssistantEvaluationToolRuntimeFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
