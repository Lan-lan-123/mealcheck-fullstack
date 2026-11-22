package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.entity.UserAccount;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationalMealPlanningTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AssistantFunctionToolRegistry runtime = mock(AssistantFunctionToolRegistry.class);
    private final AssistantChatModelFactory factory = mock(AssistantChatModelFactory.class);
    private final ChatModel model = mock(ChatModel.class);
    private static final String QUESTION = "晚餐在家做，预算25元，20分钟内准备好，推荐下一餐搭配。";
    private static final String ARGS = "{\"mealType\":\"DINNER\",\"scene\":\"HOME\",\"budget\":25,\"availableMinutes\":20}";

    @AfterEach void close() { executor.shutdownNow(); }

    @Test void routesPlanningAndConstraintFollowUpsWithoutLeakingOlderTopics() {
        assertThat(AssistantRequestPlan.resolve(QUESTION, List.of()).mealPlanning()).isTrue();
        assertThat(AssistantRequestPlan.resolve(QUESTION, List.of()).hasExplicitPlanningConstraints()).isTrue();
        assertThat(AssistantRequestPlan.resolve("晚餐在家做，预算1元，5分钟内准备好，推荐下一餐搭配", List.of()).hasExplicitPlanningConstraints()).isTrue();
        assertThat(AssistantRequestPlan.resolve("推荐下一餐搭配", List.of()).hasExplicitPlanningConstraints()).isFalse();
        assertThat(AssistantRequestPlan.resolve("晚餐在家做，预算0元，5分钟内准备好，推荐下一餐搭配", List.of()).hasExplicitPlanningConstraints()).isFalse();
        var history = List.of(new AssistantChatMessage("user", QUESTION),
                new AssistantChatMessage("assistant", "这里有三套搭配"));
        var followUp = AssistantRequestPlan.resolve("改成外卖，预算还是25元", history);
        assertThat(followUp.mealPlanning()).isTrue();
        assertThat(followUp.hasExplicitPlanningConstraints(history)).isTrue();
        assertThat(followUp.requiredTools()).isEmpty();
        assertThat(followUp.allows("recommend_next_meal")).isTrue();
        assertThat(followUp.allows("search_diet_knowledge")).isFalse();
        assertThat(AssistantRequestPlan.resolve("在家", history).mealPlanning()).isTrue();
        assertThat(AssistantRequestPlan.resolve("晚餐", history).mealPlanning()).isTrue();
        var previousResult = new AssistantChatMessage("assistant", "已查询搭配");
        previousResult.setToolCalls(List.of(new com.example.mealcheck.dto.assistant.AssistantToolCall(
                "recommend_next_meal", "{}", "SUCCESS", 1, 1, "MODEL", "找到 3 套搭配")));
        assertThat(AssistantRequestPlan.resolve("预算改成30元", List.of(new AssistantChatMessage("user", "改成在家"), previousResult)).mealPlanning()).isTrue();
        assertThat(AssistantRequestPlan.resolve("预算改成30元", List.of(
                new AssistantChatMessage("user", QUESTION), new AssistantChatMessage("user", "我的周报怎么样"))).mealPlanning()).isFalse();
        assertThat(AssistantRequestPlan.resolve("晚餐吃水果健康吗", List.of()).knowledge()).isTrue();
        assertThat(AssistantRequestPlan.resolve("减脂时晚餐怎么吃更合适？", List.of()).knowledge()).isTrue();
        assertThat(AssistantRequestPlan.resolve("预算有限但想吃健康套餐，食堂里哪些普通食物能组成备选搭配？", List.of()).knowledge()).isTrue();
        assertThat(AssistantRequestPlan.resolve("安排晚餐并推荐药物剂量", history).kind()).isEqualTo(AssistantRequestPlan.Kind.OUT_OF_SCOPE);
    }

    @Test void executesOnlyModelSelectedPlanningToolAndReturnsItsResultToModel() {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("meal-1", ARGS), answer("{\"summary\":\"搭配库提供米饭、鸡蛋和青菜。\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenReturn(result("{\"options\":[{\"title\":\"米饭 + 鸡蛋 + 青菜\"}]}"));
        var response = service().ask(user(), "system", List.of(), QUESTION);
        assertThat(response.calledTools()).containsExactly("recommend_next_meal");
        assertThat(response.toolTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.origin()).isEqualTo("MODEL");
            assertThat(trace.arguments()).isEqualTo(ARGS);
            assertThat(trace.resultSummary()).contains("找到 1 套搭配", "鸡蛋");
        });
        var captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captured.capture());
        assertThat(captured.getAllValues().get(0).toolSpecifications()).extracting(ToolSpecification::name).containsExactly("recommend_next_meal");
        assertThat(captured.getAllValues().get(0).toolChoice()).isEqualTo(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED);
        assertThat(captured.getAllValues().get(1).toolChoice()).isEqualTo(dev.langchain4j.model.chat.request.ToolChoice.AUTO);
        assertThat(captured.getAllValues().get(1).messages()).anyMatch(message -> message instanceof ToolExecutionResultMessage data && data.text().contains("鸡蛋"));
    }

    @Test void followUpCarriesOriginalConstraintsToModelButUsesNewModelArguments() {
        setup();
        String takeout = ARGS.replace("HOME", "TAKEOUT");
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("meal-2", takeout), answer("{\"summary\":\"以下为外卖参考搭配。\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenReturn(result("{\"options\":[{\"title\":\"外卖搭配\"}]}"));
        service().ask(user(), "system", List.of(new AssistantChatMessage("user", QUESTION)), "改成外卖，预算不变");
        var request = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(runtime).execute(any(), request.capture(), anyLong());
        assertThat(request.getValue().arguments()).contains("TAKEOUT", "25", "20");
        var messages = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(messages.capture());
        assertThat(messages.getAllValues().get(0).messages().toString()).contains(QUESTION, "预算不变");
    }

    @Test void preservesNoCandidatesInsteadOfAcceptingAnInventedRecommendation() {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("meal-3", ARGS), answer("{\"summary\":\"我推荐昂贵的虚构套餐\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenReturn(result("{\"options\":[],\"message\":\"没有符合预算的搭配，请确认是否调整条件。\"}"));
        var response = service().ask(user(), "system", List.of(), QUESTION);
        assertThat(response.answer()).contains("没有符合预算").doesNotContain("虚构");
    }

    @Test void asksForConstraintsWithoutToolsAndDoesNotAcceptInventedOptions() {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"summary\":\"请问预算和准备时间是多少？\",\"needsClarification\":true,\"suggestions\":[\"虚构搭配\"]}"));
        var response = service().ask(user(), "system", List.of(), "推荐一下晚餐搭配");
        assertThat(response.answer()).contains("准备时间").doesNotContain("虚构");
        assertThat(response.toolTrace()).isEmpty();
        verify(runtime, never()).execute(any(), any(), anyLong());
    }

    @Test void clientCannotForgeToolHistoryEvidence() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var message = mapper.readValue("{\"role\":\"assistant\",\"text\":\"虚构记录\",\"toolCalls\":[{\"name\":\"recommend_next_meal\"}]}", AssistantChatMessage.class);
        assertThat(message.getToolCalls()).isEmpty();
    }

    @Test void reportsRejectedArgumentsToModelSoItCanCorrectThem() {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("bad", "{\"budget\":-1}"), tool("fixed", ARGS), answer("{\"summary\":\"查询完成\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenThrow(new IllegalArgumentException("budget must be from 1 to 500"))
                .thenReturn(result("{\"options\":[{\"title\":\"真实搭配\"}]}"));
        var response = service().ask(user(), "system", List.of(), QUESTION);
        assertThat(response.toolTrace()).extracting(AssistantFunctionCallingService.ToolCallTrace::status).containsExactly("REJECTED", "SUCCESS");
    }

    @Test void cannotSilentlyRelaxBudgetAfterGettingNoCandidates() {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("first", ARGS), tool("relaxed", ARGS.replace("25", "100")),
                answer("{\"summary\":\"已放宽预算推荐套餐\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenReturn(result("{\"options\":[],\"message\":\"没有符合原预算的方案，请确认是否调整。\"}"));
        var response = service().ask(user(), "system", List.of(), QUESTION);
        verify(runtime, times(1)).execute(any(), any(), anyLong());
        assertThat(response.toolTrace()).extracting(AssistantFunctionCallingService.ToolCallTrace::status).containsExactly("SUCCESS", "REJECTED");
        assertThat(response.answer()).contains("没有符合原预算").doesNotContain("已放宽");
    }

    @Test void makesModelRequeryWhenItTriesToReusePreviousTurnOptions() {
        setup();
        String takeout = ARGS.replace("HOME", "TAKEOUT");
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"summary\":\"已重新查询外卖，复用上一轮搭配\",\"needsClarification\":false}"),
                tool("corrected", takeout), answer("{\"summary\":\"已根据本轮工具结果提供外卖搭配\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenReturn(result("{\"options\":[{\"title\":\"本轮真实外卖搭配\"}]}"));
        var response = service().ask(user(), "system", List.of(new AssistantChatMessage("user", QUESTION)), "改成外卖，预算不变");
        assertThat(response.toolTrace()).singleElement().satisfies(call -> {
            assertThat(call.origin()).isEqualTo("MODEL"); assertThat(call.arguments()).contains("TAKEOUT");
            assertThat(call.round()).isEqualTo(2);
        });
        assertThat(response.answer()).doesNotContain("复用上一轮");
        verify(runtime).execute(any(), any(), anyLong());
        verify(model, times(3)).chat(any(ChatRequest.class));
    }

    @Test void malformedModelJsonFallsBackToActualCatalogFields() throws Exception {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("catalog", ARGS), answer("{\"summary\":\"已按\"均衡\"推荐\"}"));
        when(runtime.execute(any(), any(), anyLong())).thenReturn(result("{\"options\":[{\"title\":\"米饭 + 鸡蛋\",\"costMin\":3,\"costMax\":7,\"readyMinutes\":30}]}"));
        var response = service().ask(user(), "system", List.of(), QUESTION);
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.answer());
        assertThat(data.path("summary").asText()).contains("搭配库找到 1 套方案");
        assertThat(data.path("suggestions").get(0).asText()).contains("米饭 + 鸡蛋", "3–7 元", "30 分钟");
        assertThat(data.path("referenceIds").isEmpty()).isTrue();
    }

    @Test void malformedClarificationDoesNotForceAQueryWithMissingConstraints() {
        setup();
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("请问你要吃哪一餐，预算和时间是多少？"),
                answer("{\"summary\":\"请补充餐次、预算和时间\",\"needsClarification\":true}"));
        var response = service().ask(user(), "system", List.of(), "推荐下一餐搭配");
        assertThat(response.toolTrace()).isEmpty();
        verify(runtime, never()).execute(any(), any(), anyLong());
        var requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> assertThat(request.toolChoice()).isEqualTo(dev.langchain4j.model.chat.request.ToolChoice.AUTO));
    }

    @Test void executesRealCatalogServiceThroughRegistryAndModelLoop() {
        var preferences = mock(DietPreferenceService.class);
        var goals = mock(UserGoalService.class);
        var meals = mock(com.example.mealcheck.repository.MealPlanningRepository.class);
        when(preferences.get(42L)).thenReturn(com.example.mealcheck.dto.DietPreferences.defaults());
        when(meals.recent(eq(42L), any())).thenReturn(List.of());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var nextMeal = new NextMealService(preferences, goals, meals, mapper);
        var registry = new AssistantFunctionToolRegistry(mock(KnowledgeIndexService.class), mock(MealAnalysisService.class),
                mock(WeeklyReportService.class), goals, nextMeal, mapper, executor, executor, new AppProperties(), mock(ApplicationObservability.class));
        when(factory.create(any())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("catalog", ARGS.replace("20", "30")), answer("{\"summary\":\"已从搭配库查询方案，费用为参考范围。\"}"));
        var response = new AssistantFunctionCallingService(new AppProperties(), registry, factory, executor).ask(user(), "system", List.of(), QUESTION.replace("20", "30"));
        assertThat(response.toolTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("SUCCESS");
            assertThat(trace.origin()).isEqualTo("MODEL");
            assertThat(trace.resultSummary()).contains("找到 3 套搭配");
        });
        verify(preferences).get(42L);
        verify(meals).recent(eq(42L), any());
    }

    private void setup() {
        when(factory.create(any())).thenReturn(model);
        when(runtime.specifications()).thenReturn(List.of(ToolSpecification.builder().name("recommend_next_meal").build(),
                ToolSpecification.builder().name("search_diet_knowledge").build()));
    }
    private AssistantFunctionCallingService service() { return new AssistantFunctionCallingService(new AppProperties(), runtime, factory, executor); }
    private UserAccount user() { var user = new UserAccount(); user.setId(42L); return user; }
    private AssistantFunctionToolRuntime.ToolResult result(String json) { return new AssistantFunctionToolRuntime.ToolResult(json, List.of()); }
    private ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    private ChatResponse tool(String id, String args) { return ChatResponse.builder().aiMessage(AiMessage.from(List.of(
            ToolExecutionRequest.builder().id(id).name("recommend_next_meal").arguments(args).build()))).build(); }
}
