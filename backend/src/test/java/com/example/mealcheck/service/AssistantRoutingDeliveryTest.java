package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.*;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.skill.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantRoutingDeliveryTest {
    final AppProperties properties=new AppProperties();
    final UserAccountRepository users=mock(UserAccountRepository.class);
    final KnowledgeIndexService index=mock(KnowledgeIndexService.class);
    final UserDietProfileService profiles=mock(UserDietProfileService.class);
    final AssistantToolOrchestrator tools=mock(AssistantToolOrchestrator.class);
    final AssistantConversationService conversations=mock(AssistantConversationService.class);
    final AssistantSkillRegistry skills=mock(AssistantSkillRegistry.class);
    final AssistantMemoryService memory=mock(AssistantMemoryService.class);
    final AssistantFunctionCallingService functions=mock(AssistantFunctionCallingService.class);
    final RedisCacheService cache=mock(RedisCacheService.class);
    final UserAccount user=new UserAccount();
    LangChainDietAssistantService service;
    @BeforeEach void prepare() {
        properties.getAi().setApiKey("unit-test-key");properties.getAi().setBaseUrl("http://unused.invalid");properties.getAi().setModel("fixture");
        user.setId(1L);user.setUsername("fixture");var conversation=new AssistantConversation();conversation.setId(2L);conversation.setUser(user);
        when(users.findById(1L)).thenReturn(Optional.of(user));when(conversations.resolve(eq(user),isNull(),anyString())).thenReturn(conversation);
        when(conversations.memoryHistory(any(),anyInt(),anyInt())).thenReturn(new AssistantConversationService.MemoryHistory(List.of(),List.of(),List.of(),0,0,0));
        when(skills.select(anyString(),anyList())).thenReturn(new AssistantSkillPlan("diet","饮食","FAT_LOSS","","参考证据"));
        when(memory.prepare(any(),anyString(),anyString(),any(AssistantConversationService.MemoryHistory.class),anyList()))
                .thenAnswer(call->new AssistantMemoryService.MemoryContext(List.of(),"","{}",call.getArgument(1),0));
        service=new LangChainDietAssistantService(properties,users,mock(MealRecordRepository.class),index,new ObjectMapper(),mock(AiStatusService.class),
                cache,profiles,tools,conversations,mock(ApplicationObservability.class),skills,memory,
                mock(AssistantChatModelFactory.class),functions,new AssistantFunctionPromptFactory());
    }
    @Test void knowledgeUsesDeterministicRetrievalPathWhenFunctionCallingDisabledAndBuffersStream() {
        var visible=new ArrayList<String>();
        when(functions.askKnowledge(eq(user),anyString(),anyList(),anyString())).thenAnswer(call->{
            assertThat(visible).isEmpty();
            return new AssistantFunctionCallingService.FunctionCallingResult(
                    "{\"summary\":\"核对产品标签。\",\"suggestions\":[],\"weeklyTrend\":\"records are insufficient\",\"referenceIds\":[7],\"refused\":false}",
                    List.of(new KnowledgeSnippet(7L,"产品标签","核对产品标签。",.9)),List.of("search_diet_knowledge"),1,List.of());
        });
        var delivery=new AssistantStreamDelivery((kind,data)->{if("delta".equals(kind))visible.add(((Map<?,?>)data).get("text").toString());});
        var result=service.deliver(new UserPrincipal(user),"豆奶如何选择和记录？",null,List.of(),delivery);
        assertThat(visible).containsExactly("核对产品标签。");assertThat(result.getWeeklyTrend()).isEmpty();
        assertThat(result.getReferences()).singleElement().satisfies(ref->assertThat(ref.getId()).isEqualTo(7L));
        verify(functions,never()).ask(any(),anyString(),anyList(),anyString());
        verify(profiles,never()).getOrRefresh(any());verify(tools,never()).gather(any(),any(),anyString());
    }
    @Test void outOfScopeDoesNotReadProfileOrStartRemoteGeneration() {
        var response=service.ask(new UserPrincipal(user),"劳动合同纠纷怎么起诉？",null,List.of());
        assertThat(response.getAnswer()).contains("服务范围");assertThat(response.getReferences()).isEmpty();
        verifyNoInteractions(functions,index,profiles,tools,skills);
        verify(memory,never()).prepare(any(),anyString(),anyString(),any(AssistantConversationService.MemoryHistory.class),anyList());
    }
    @Test void failedKnowledgeVerificationDoesNotFallBackToGenericDietAdvice() {
        when(functions.askKnowledge(eq(user),anyString(),anyList(),anyString())).thenThrow(new IllegalStateException("fixture failure"));
        var response=service.ask(new UserPrincipal(user),"酸奶怎么选？",null,List.of());
        assertThat(response.getAnswer()).contains("模型服务暂不可用");assertThat(response.getReferences()).isEmpty();
        assertThat(response.getAnswerStatus()).isEqualTo("DEGRADED");
        assertThat(response.getFailureCode()).isEqualTo("MODEL_SERVICE_UNAVAILABLE");
        verify(tools,never()).gather(any(),any(),anyString());
    }
    @Test void reviewedQuotaMetadataSurvivesFormattingAndFailureIsNotCachedAsNormalHistory() {
        String raw=AssistantGroundingGuard.unavailable(new AiFailure.ProviderException("MODEL_QUOTA_EXHAUSTED",403),null);
        when(functions.askKnowledge(eq(user),anyString(),anyList(),anyString())).thenReturn(
                new AssistantFunctionCallingService.FunctionCallingResult(raw,List.of(),List.of(),1,List.of()));
        var result=service.ask(new UserPrincipal(user),"酸奶怎么选？",null,List.of());
        assertThat(result.getAnswerStatus()).isEqualTo("DEGRADED");
        assertThat(result.getFailureCode()).isEqualTo("MODEL_QUOTA_EXHAUSTED");assertThat(result.getUpstreamStatus()).isEqualTo(403);
        verify(cache,never()).setJson(anyString(),any(),any());
        verify(conversations).appendTurn(any(),anyString(),anyString(),anyString(),eq("ERROR"),anyList(),anyList(),argThat(r->"DEGRADED".equals(r.getAnswerStatus()) && "MODEL_QUOTA_EXHAUSTED".equals(r.getFailureCode())));
        verify(tools,never()).remember(any(),any(),anyString());
    }
}
