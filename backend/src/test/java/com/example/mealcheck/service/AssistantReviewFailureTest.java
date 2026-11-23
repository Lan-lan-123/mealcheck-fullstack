package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.entity.UserAccount;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executors;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AssistantReviewFailureTest {
    @Test void reviewQuotaDoesNotRegenerateOrBecomeInsufficientKnowledge() {
        var registry=mock(AssistantFunctionToolRegistry.class);var factory=mock(AssistantChatModelFactory.class);
        var generation=mock(ChatModel.class);var review=mock(ChatModel.class);var executor=Executors.newSingleThreadExecutor();
        when(factory.create(any())).thenReturn(generation);
        when(factory.create(any(),eq(0.0),eq("diet-assistant-evidence-review"))).thenReturn(review);
        when(registry.specifications()).thenReturn(List.of());
        var source=new KnowledgeSnippet(7L,"早餐","早餐可以选择主食。",1);
        when(registry.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("{}",List.of(source)));
        when(generation.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"claims\":[{\"kind\":\"ANSWER\",\"text\":\"早餐可以选择主食。\",\"evidence\":[{\"sourceId\":7,\"passageIds\":[0]}]}]}" )).build());
        when(review.chat(any(ChatRequest.class))).thenThrow(new AiFailure.ProviderException("MODEL_QUOTA_EXHAUSTED",403));
        try {
            var user=new UserAccount();user.setId(1L);user.setUsername("fixture");
            var result=new AssistantFunctionCallingService(new AppProperties(),registry,factory,executor).askKnowledge(user,"system",List.of(),"早餐主食如何选择？");
            assertThat(result.answer()).contains("MODEL_QUOTA_EXHAUSTED","DEGRADED").doesNotContain("知识库证据不足");
            verify(generation,times(1)).chat(any(ChatRequest.class));verify(review,times(1)).chat(any(ChatRequest.class));
            verify(registry,times(1)).execute(any(),any(),anyLong());
        } finally {executor.shutdownNow();}
    }
    @Test void reviewPayloadKeepsBoundEvidenceAndNeighborConditionsWithoutUnrelatedSources() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var input=json.readTree("{\"claims\":[{\"text\":\"先核对配料。\",\"evidence\":[{\"sourceId\":7,\"passageIds\":[1]}]}]}");
        var sources=List.of(new KnowledgeSnippet(7L,"标签","如果只知道名称则保留未知。先核对配料。没有比例不能填百分数。无关后文。",1),
                new KnowledgeSnippet(8L,"无关","UNRELATED".repeat(4000),1));
        var prepared=AssistantGroundingGuard.prepare(input,sources);
        assertThat(prepared.usable()).isTrue();
        String prompt=AssistantGroundingGuard.reviewPrompt("问题",prepared.answer(),sources);
        assertThat(prompt).contains("如果只知道名称则保留未知","没有比例不能填百分数").doesNotContain("UNRELATED","无关后文");
    }
}
