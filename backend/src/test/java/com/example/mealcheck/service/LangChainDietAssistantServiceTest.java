package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.dto.assistant.AssistantProactiveAdviceResponse;
import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserDietProfile;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChainDietAssistantServiceTest {

    @Test
    void fallbackAnswerIncludesProfileDrivenSuggestions() {
        AppProperties properties = new AppProperties();
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        MealRecordRepository mealRecordRepository = mock(MealRecordRepository.class);
        KnowledgeIndexService knowledgeIndexService = mock(KnowledgeIndexService.class);
        AiStatusService aiStatusService = mock(AiStatusService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        UserDietProfileService profileService = mock(UserDietProfileService.class);
        AssistantToolOrchestrator toolOrchestrator = mock(AssistantToolOrchestrator.class);
        AssistantConversationService conversationService = mock(AssistantConversationService.class);
        AssistantSkillRegistry skillRegistry = mock(AssistantSkillRegistry.class);
        AssistantMemoryService memoryService = mock(AssistantMemoryService.class);
        AssistantFunctionCallingService functionCallingService = mock(AssistantFunctionCallingService.class);
        AssistantChatModelFactory modelFactory = mock(AssistantChatModelFactory.class);
        LangChainDietAssistantService service = new LangChainDietAssistantService(
                properties,
                userRepository,
                mealRecordRepository,
                knowledgeIndexService,
                new ObjectMapper(),
                aiStatusService,
                redisCacheService,
                profileService,
                toolOrchestrator,
                conversationService,
                mock(ApplicationObservability.class),
                skillRegistry,
                memoryService,
                modelFactory,
                functionCallingService,
                new AssistantFunctionPromptFactory()
        );

        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setUsername("demo");
        user.setPasswordHash("hash");
        UserDietProfile profile = new UserDietProfile();
        profile.setTotalMeals(12);
        profile.setPreferredGoal("fat_loss");
        profile.setProfileSummary("最近记录显示你更关注减脂。");

        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(99L);
        conversation.setUser(user);
        conversation.setTitle("减脂");
        WeeklyReportResponse report = new WeeklyReportResponse();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(profileService.getOrRefresh(user)).thenReturn(profile);
        when(profileService.commonFoods(profile)).thenReturn(List.of("米饭", "鸡腿", "青菜"));
        when(profileService.commonRisks(profile)).thenReturn(List.of("高油"));
        when(profileService.promptText(profile)).thenReturn("profile");
        when(redisCacheService.getJson(any(), any())).thenReturn(Optional.empty());
        when(conversationService.resolve(eq(user), eq(null), any())).thenReturn(conversation);
        when(conversationService.memoryHistory(eq(conversation), anyInt(), anyInt()))
                .thenReturn(new AssistantConversationService.MemoryHistory(
                        List.of(), List.of(), List.of(), 0, 0, 0));
        when(memoryService.prepare(eq(conversation), any(), any(),
                any(AssistantConversationService.MemoryHistory.class), anyList()))
                .thenAnswer(invocation -> new AssistantMemoryService.MemoryContext(
                        List.of(), "", "intent=FAT_LOSS", invocation.getArgument(1, String.class), 0));
        when(memoryService.selectWorkingMessages(any(), any())).thenReturn(List.of());
        when(skillRegistry.select(any(), anyList())).thenReturn(new AssistantSkillPlan(
                "fat-loss", "减脂建议", "FAT_LOSS", "减脂 蛋白质 蔬菜", "保持可持续减脂"));
        when(toolOrchestrator.gather(eq(user), eq(conversation), any())).thenReturn(new AssistantToolOrchestrator.AssistantToolContext(
                List.of(),
                "fat_loss",
                report,
                List.of(),
                List.of(),
                new AssistantProactiveAdviceResponse(List.of())
        ));

        AssistantResponse response = service.ask(new UserPrincipal(user), "我最近的饮食记录怎么调整更适合减脂？", null, List.of());

        assertThat(response.getSuggestions()).anyMatch(text -> text.contains("米饭"));
        assertThat(response.getSuggestions()).anyMatch(text -> text.contains("高油"));
        assertThat(response.getSuggestions()).anyMatch(text -> text.contains("fat_loss"));
        verify(redisCacheService).getJson(eq("assistant:history:demo:99"), any());
        verify(redisCacheService).setJson(eq("assistant:history:demo:99"), any(), any());
        verifyNoInteractions(functionCallingService);
        var preferences = mock(DietPreferenceService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dietPreferences",preferences);
        when(preferences.get(7L)).thenReturn(new com.example.mealcheck.dto.DietPreferences(
                com.example.mealcheck.dto.DietPreferences.Style.OMNIVORE,List.of("米饭"),List.of(),1));
        var constrained = service.ask(new UserPrincipal(user), "我最近的饮食记录怎么调整更适合减脂？", null, List.of());
        assertThat(constrained.getSuggestions()).noneMatch(text -> text.contains("米饭"));
        assertThat(constrained.getAnswer()).doesNotContain("米饭");
        verify(preferences).get(7L);
        // With saved restrictions, provider output is checked before any visible delta.
        properties.getAi().setApiKey("test-key-not-a-provider-key");
        properties.getAi().setBaseUrl("http://unused.invalid");
        properties.getAi().setModel("stub");
        when(preferences.get(7L)).thenReturn(new com.example.mealcheck.dto.DietPreferences(
                com.example.mealcheck.dto.DietPreferences.Style.VEGAN,List.of(),List.of(),2));
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(modelFactory.create(any(java.time.Duration.class),eq(.45),eq("diet-assistant"))).thenReturn(model);
        when(model.chat(anyString())).thenReturn("{\"summary\":\"下一餐可选鸡蛋\",\"suggestions\":[\"鸡蛋补充蛋白\",\"搭配青菜\"],\"riskLevel\":\"LOW\",\"weeklyTrend\":\"记录不足\"}");
        var visible = new java.util.ArrayList<String>();
        var delivery = new AssistantStreamDelivery((kind,data) -> {
            if ("delta".equals(kind)) visible.add(((java.util.Map<?,?>)data).get("text").toString());
        });
        var streamed = service.deliver(new UserPrincipal(user),"根据我的记录给我下一餐建议",null,List.of(),delivery);
        assertThat(streamed.getAnswer()).isEqualTo(DietPreferenceService.SAFE_SUGGESTION);
        assertThat(String.join("",visible)).isEqualTo(streamed.getAnswer()).doesNotContain("鸡蛋");
        verify(modelFactory,never()).stream(anyString(),any());
        verify(model).chat(org.mockito.ArgumentMatchers.argThat((String text) -> text.contains("纯素") && text.contains("优先于历史推测")));
    }
}
