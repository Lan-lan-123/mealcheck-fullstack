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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
                conversationService
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
        conversation.setUser(user);
        conversation.setTitle("减脂");
        WeeklyReportResponse report = new WeeklyReportResponse();

        when(userRepository.findByUsername("demo")).thenReturn(Optional.of(user));
        when(profileService.getOrRefresh(user)).thenReturn(profile);
        when(profileService.commonFoods(profile)).thenReturn(List.of("米饭", "鸡腿", "青菜"));
        when(profileService.commonRisks(profile)).thenReturn(List.of("高油"));
        when(profileService.promptText(profile)).thenReturn("profile");
        when(redisCacheService.getJson(any(), any())).thenReturn(Optional.empty());
        when(conversationService.resolve(eq(user), eq(null), any())).thenReturn(conversation);
        when(conversationService.history(conversation)).thenReturn(List.of());
        when(toolOrchestrator.gather(eq(user), any())).thenReturn(new AssistantToolOrchestrator.AssistantToolContext(
                List.of(),
                "fat_loss",
                report,
                List.of(),
                new AssistantProactiveAdviceResponse(List.of())
        ));

        AssistantResponse response = service.ask(new UserPrincipal(user), "今天怎么吃更适合减脂？", null, List.of());

        assertThat(response.getSuggestions()).anyMatch(text -> text.contains("米饭"));
        assertThat(response.getSuggestions()).anyMatch(text -> text.contains("高油"));
        assertThat(response.getSuggestions()).anyMatch(text -> text.contains("fat_loss"));
    }
}
