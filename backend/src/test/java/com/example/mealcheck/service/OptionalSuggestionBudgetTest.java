package com.example.mealcheck.service;
import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OptionalSuggestionBudgetTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode fixture() throws Exception {return json.readTree(getClass().getResourceAsStream("/evaluation/optional_suggestion_budget_capture.json"));}
    List<KnowledgeSnippet> sources(JsonNode data) throws Exception {
        var result=new ArrayList<KnowledgeSnippet>();for(var source:data.path("sources"))result.add(json.treeToValue(source,KnowledgeSnippet.class));return result;
    }
    @Test void capturedSevenClausesPreserveCoreAndExplainOnlyOptionalBudgetOmission() throws Exception {
        var data=fixture();var prepared=AssistantGroundingGuard.prepare(data.path("candidate"),sources(data));
        assertThat(prepared.issues()).singleElement().satisfies(i->assertThat(i.code()).isEqualTo("OPTIONAL_SUGGESTION_BUDGET"));
        assertThat(prepared.answer().path("claims")).hasSize(2);
        var checked=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),GroundingReviewFixture.verdict(prepared.answer(),Set.of()));
        var answer=json.readTree(AssistantGroundingGuard.finish(checked,prepared.issues(),true));
        assertThat(answer.path("summary").asText()).contains("不能直接当作完全没有","晚上是否适合","预算限制已省略").doesNotContain("其余未确认");
        assertThat(answer.path("suggestions")).isEmpty();assertThat(answer.path("optionalSuggestionsOmitted").asBoolean()).isTrue();
    }
    @Test void fittingAdviceSurvivesButUnsupportedAdviceIsNeverRelabeledAsBudgetOnly() throws Exception {
        var data=fixture();var candidate=(ObjectNode)data.path("candidate").deepCopy();
        ((ObjectNode)candidate.path("claims").path(2)).put("text","晚间想喝热饮时，先确认实际产品。");
        var fits=AssistantGroundingGuard.prepare(candidate,sources(data));assertThat(fits.issues()).isEmpty();assertThat(fits.answer().path("suggestions")).hasSize(1);
        ((ObjectNode)candidate.path("claims").path(2).path("evidence").path(0)).put("sourceId",999999);
        var invalid=AssistantGroundingGuard.prepare(candidate,sources(data));
        assertThat(invalid.issues()).anyMatch(i->i.code().equals("UNKNOWN_SOURCE")).noneMatch(i->i.code().equals("OPTIONAL_SUGGESTION_BUDGET"));
    }
    @Test void missingInputStillShowsUncertaintyAndCoreBudgetStillFailsNormally() throws Exception {
        var data=fixture();var prepared=AssistantGroundingGuard.prepare(data.path("candidate"),sources(data));
        prepared.answer().put("informationStatus","MISSING_INPUT");
        var answer=json.readTree(AssistantGroundingGuard.finish(prepared,prepared.issues(),true));
        assertThat(answer.path("summary").asText()).contains("其余未确认");assertThat(answer.path("optionalSuggestionsOmitted").asBoolean()).isFalse();
        var candidate=(ObjectNode)data.path("candidate").deepCopy();((ObjectNode)candidate.path("claims").path(2)).put("kind","ANSWER");
        assertThat(AssistantGroundingGuard.prepare(candidate,sources(data)).issues()).anyMatch(i->i.code().equals("CLAIM_TOO_COMPLEX"));
    }
    @Test void budgetOmissionNeedsExactlyOneGenerationAndOneReviewNoSupplementOrRepair() throws Exception {
        var data=fixture();var sources=sources(data);var prepared=AssistantGroundingGuard.prepare(data.path("candidate"),sources);
        var tools=mock(AssistantFunctionToolRegistry.class);when(tools.specifications()).thenReturn(List.of());
        when(tools.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("evidence",sources));
        var factory=mock(AssistantChatModelFactory.class);var generate=mock(ChatModel.class);var review=mock(ChatModel.class);
        when(factory.create(any())).thenReturn(generate);when(factory.create(any(),eq(0.0),eq("diet-assistant-evidence-review"))).thenReturn(review);
        when(generate.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(data.path("candidate").toString())).build());
        when(review.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(GroundingReviewFixture.verdict(prepared.answer(),Set.of()).toString())).build());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var service=new AssistantFunctionCallingService(new AppProperties(),tools,factory,executor);
            String result=service.askWithRuntime("system",List.of(),"低因咖啡是不是完全没有咖啡因，晚上能随便喝吗？",tools).answer();
            assertThat(result).contains("预算限制已省略").doesNotContain("其余未确认");
            verify(generate,times(1)).chat(any(ChatRequest.class));verify(review,times(1)).chat(any(ChatRequest.class));verify(tools,times(1)).execute(any(),any(),anyLong());
        } finally {executor.shutdownNow();}
    }
}
