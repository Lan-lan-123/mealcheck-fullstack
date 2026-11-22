package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.assistant.AssistantToolCall;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MealPlanningAssistantDeliveryTest {
    private final AppProperties properties = new AppProperties();
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AssistantConversationService conversations = mock(AssistantConversationService.class);
    private final AssistantFunctionCallingService functions = mock(AssistantFunctionCallingService.class);
    private final AssistantToolOrchestrator orchestrator = mock(AssistantToolOrchestrator.class);
    private final UserAccount user = new UserAccount();
    private final AssistantConversation conversation = new AssistantConversation();

    @Test void browserSseUsesFunctionCallingAndPublishesAndPersistsActualTrace() {
        var service = service(true);
        var trace = new AssistantFunctionCallingService.ToolCallTrace("recommend_next_meal", "{\"budget\":25}", "SUCCESS", 1, 12, "MODEL", "找到 3 套搭配");
        when(functions.ask(eq(user), anyString(), anyList(), anyString())).thenReturn(
                new AssistantFunctionCallingService.FunctionCallingResult("{\"summary\":\"找到三套符合条件的搭配\"}", List.of(), List.of("recommend_next_meal"), 1, List.of(trace)));
        when(conversations.appendTurn(eq(conversation), anyString(), anyString(), anyString(), eq("COMPLETE"), anyList(),anyList(),any())).thenReturn(99L);
        List<String> visible = new ArrayList<>();
        var delivery = new AssistantStreamDelivery((event, data) -> {
            if ("delta".equals(event)) visible.add(((java.util.Map<?, ?>) data).get("text").toString());
        });
        var response = service.deliver(new UserPrincipal(user), "晚餐在家做，预算25元，20分钟内做好，推荐搭配", null, List.of(), delivery);
        assertThat(response.getConversationId()).isEqualTo(7L);
        assertThat(response.getMessageId()).isEqualTo(99L);
        assertThat(response.getToolCalls()).containsExactly(new AssistantToolCall("recommend_next_meal", "{\"budget\":25}", "SUCCESS", 1, 12, "MODEL", "找到 3 套搭配"));
        assertThat(String.join("", visible)).contains("三套");
        verify(functions).ask(eq(user), contains("recommend_next_meal"), anyList(), anyString());
        verifyNoInteractions(orchestrator);
    }

    @Test void disabledFeatureDoesNotPretendToCallToolsOrQueryUnrelatedData() {
        var service = service(false);
        var response = service.ask(new UserPrincipal(user), "推荐下一餐搭配", null, List.of());
        assertThat(response.getAnswer()).contains("暂不可用", "下一餐搭配");
        assertThat(response.getToolCalls()).isEmpty();
        verifyNoInteractions(functions, orchestrator);
    }

    @Test void failedModelLoopRetainsItsRealTraceAndDoesNotInventARecommendation() {
        var service = service(true);
        var trace = new AssistantFunctionCallingService.ToolCallTrace("recommend_next_meal", "{}", "ERROR", 1, 10);
        when(functions.ask(eq(user), anyString(), anyList(), anyString())).thenThrow(
                new AssistantFunctionCallingService.FunctionCallingExecutionException("failed", null, List.of(trace), 1));
        var response = service.ask(new UserPrincipal(user), "推荐下一餐搭配", null, List.of());
        assertThat(response.getAnswer()).contains("查询暂时未完成", "没有为本次请求生成新的推荐");
        assertThat(response.getToolCalls()).singleElement().extracting(AssistantToolCall::status).isEqualTo("ERROR");
        verifyNoInteractions(orchestrator);
    }

    private LangChainDietAssistantService service(boolean enabled) {
        user.setId(42L); user.setUsername("planner");
        conversation.setId(7L); conversation.setUser(user);
        properties.getAi().setApiKey("test-only"); properties.getAi().setBaseUrl("http://unused.invalid");
        properties.getAssistantFunctionCalling().setEnabled(enabled);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(conversations.resolve(eq(user), isNull(), anyString())).thenReturn(conversation);
        when(conversations.memoryHistory(eq(conversation), anyInt(), anyInt())).thenReturn(
                new AssistantConversationService.MemoryHistory(List.of(), List.of(), List.of(), 0, 0, 0));
        var cache = mock(RedisCacheService.class);
        when(cache.getJson(anyString(), any())).thenReturn(Optional.empty());
        return new LangChainDietAssistantService(properties, users, mock(MealRecordRepository.class), mock(KnowledgeIndexService.class),
                new ObjectMapper(), mock(AiStatusService.class), cache, mock(UserDietProfileService.class), orchestrator, conversations,
                mock(ApplicationObservability.class), mock(AssistantSkillRegistry.class), mock(AssistantMemoryService.class),
                mock(AssistantChatModelFactory.class), functions, new AssistantFunctionPromptFactory());
    }
}
