package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEvaluationRunnerTest {

    @Test
    void runsSharedFunctionCallingPathAndNormalizesStructuredAnswer() throws Exception {
        AssistantFunctionCallingService functionCalling = mock(AssistantFunctionCallingService.class);
        AssistantEvaluationToolRuntimeFactory runtimeFactory = mock(AssistantEvaluationToolRuntimeFactory.class);
        AssistantSkillRegistry skillRegistry = mock(AssistantSkillRegistry.class);
        AssistantFunctionToolRuntime runtime = mock(AssistantFunctionToolRuntime.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode context = mapper.readTree("""
                {"profileFacts":["用户对花生过敏"],
                 "recentMeals":[{"foods":["不应直接泄漏进提示词"]}],
                 "history":[{"role":"USER","text":"上一问"}]}
                """);
        AssistantSkillPlan plan = new AssistantSkillPlan(
                "personalized", "个性化", "PERSONALIZED", "", "使用用户数据");
        when(skillRegistry.select(eq("下一问"), anyList())).thenReturn(plan);
        when(runtimeFactory.create(context, "knowledge")).thenReturn(runtime);
        when(functionCalling.askWithRuntime(anyString(), anyList(), eq("下一问"), same(runtime)))
                .thenReturn(new AssistantFunctionCallingService.FunctionCallingResult(
                        """
                        {"summary":"总结","suggestions":["建议一","建议二"],
                         "riskLevel":"LOW","weeklyTrend":"记录不足"}
                        """,
                        List.of(),
                        List.of("get_my_recent_meals"),
                        1,
                        List.of(new AssistantFunctionCallingService.ToolCallTrace(
                                "get_my_recent_meals", "{}", "SUCCESS", 1, 2L))));
        AssistantEvaluationRunner runner = new AssistantEvaluationRunner(
                functionCalling,
                runtimeFactory,
                new AssistantFunctionPromptFactory(),
                skillRegistry,
                mapper);

        AssistantEvaluationRunner.EvaluationResult result = runner.run(
                "下一问", context, "knowledge");

        assertThat(result.executionPath()).isEqualTo("PRODUCTION_FUNCTION_CALLING");
        assertThat(result.selectedSkill()).isEqualTo("PERSONALIZED");
        assertThat(result.answer()).contains("总结", "- 建议一", "本周趋势：记录不足");
        assertThat(result.calledTools()).containsExactly("get_my_recent_meals");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AssistantChatMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(functionCalling).askWithRuntime(
                prompt.capture(), history.capture(), eq("下一问"), same(runtime));
        assertThat(prompt.getValue()).contains("用户对花生过敏");
        assertThat(prompt.getValue()).doesNotContain("不应直接泄漏进提示词");
        assertThat(history.getValue()).singleElement().satisfies(message -> {
            assertThat(message.getRole()).isEqualTo("user");
            assertThat(message.getText()).isEqualTo("上一问");
        });
    }
}
