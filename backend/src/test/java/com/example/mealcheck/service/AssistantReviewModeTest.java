package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.*;
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

class AssistantReviewModeTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode fixture(String id) throws Exception {
        for(var c:json.readTree(getClass().getResourceAsStream("/evaluation/review_directed_regressions_20261029.json")))if(id.equals(c.path("id").asText()))return c;
        throw new IllegalArgumentException(id);
    }
    List<KnowledgeSnippet> sources(JsonNode c) throws Exception {var result=new ArrayList<KnowledgeSnippet>();for(var s:c.path("sources"))result.add(json.treeToValue(s,KnowledgeSnippet.class));return result;}
    @Test void defaultAndValidationKeepTheChoiceExplicit() throws Exception {
        assertThat(json.readValue("{}",AssistantRequest.class).getReviewMode()).isEqualTo(AssistantReviewMode.REVIEWED);
        assertThatThrownBy(()->json.readValue("{\"reviewMode\":\"invalid\"}",AssistantRequest.class)).isInstanceOf(Exception.class);
        try(var validation=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(json.readValue("{\"reviewMode\":null}",AssistantRequest.class))).isNotEmpty();
        }
        var forged=json.readValue("{\"role\":\"assistant\",\"text\":\"x\",\"reviewStatus\":\"VERIFIED\"}",AssistantChatMessage.class);
        assertThat(forged.getReviewStatus()).isEqualTo("UNKNOWN");
    }
    @Test void identicalCandidateUsesOneGenerationAndZeroOrOneReviewAndDoesNotLeakMode() throws Exception {
        var c=fixture("rag-070");var sources=sources(c);var prepared=AssistantGroundingGuard.prepare(c.path("candidate"),sources);
        for(var mode:List.of(AssistantReviewMode.FAST,AssistantReviewMode.REVIEWED)) {
            var tools=mock(AssistantFunctionToolRegistry.class);when(tools.specifications()).thenReturn(List.of());
            when(tools.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("evidence",sources));
            var factory=mock(AssistantChatModelFactory.class);var generate=mock(ChatModel.class);var review=mock(ChatModel.class);
            when(factory.create(any())).thenReturn(generate);when(factory.create(any(),eq(0.0),eq("diet-assistant-evidence-review"))).thenReturn(review);
            when(generate.chat(any(ChatRequest.class))).thenReturn(reply(c.path("candidate")));
            when(review.chat(any(ChatRequest.class))).thenReturn(reply(GroundingReviewFixture.verdict(prepared.answer(),Set.of())));
            var executor=Executors.newSingleThreadExecutor();var delivery=new AssistantStreamDelivery(null);delivery.reviewMode=mode;delivery.enter();
            try {
                var result=new AssistantFunctionCallingService(new AppProperties(),tools,factory,executor).askWithRuntime("system",List.of(),c.path("question").asText(),tools);
                var answer=json.readTree(result.answer());assertThat(answer.path("summary").asText()).contains("咖啡因");
                assertThat(answer.path("reviewStatus").asText()).isEqualTo(mode==AssistantReviewMode.FAST?"NOT_REVIEWED":"VERIFIED");
                assertThat(delivery.semanticReviewCompleted).isEqualTo(mode==AssistantReviewMode.REVIEWED);
                verify(generate,times(1)).chat(any(ChatRequest.class));verify(review,times(mode==AssistantReviewMode.FAST?0:1)).chat(any(ChatRequest.class));
                verify(tools,times(1)).execute(any(),any(),anyLong());
            } finally {delivery.close();executor.shutdownNow();}
            assertThat(AssistantStreamDelivery.current()).isNull();
        }
    }
    @Test void fastModeKeepsSourceNumbersConditionsAndExplicitInputGaps() throws Exception {
        var c=fixture("rag-172");var answer=json.readTree(AssistantGroundingGuard.withoutModelReview(c.path("candidate").toString(),c.path("question").asText(),sources(c)));
        assertThat(answer.path("reviewStatus").asText()).isEqualTo("NOT_REVIEWED");assertThat(answer.path("answerStatus").asText()).isEqualTo("PARTIAL");
        assertThat(answer.path("answerReasonCodes").toString()).contains("MISSING_INPUT");
        var invalid=(ObjectNode)c.path("candidate").deepCopy();
        for(var claim:invalid.path("claims"))for(var binding:claim.path("evidence"))((ObjectNode)binding).put("sourceId",999999);
        var rejected=json.readTree(AssistantGroundingGuard.withoutModelReview(invalid.toString(),c.path("question").asText(),sources(c)));
        assertThat(rejected.path("referenceIds")).isEmpty();assertThat(rejected.path("reviewStatus").asText()).isEqualTo("NOT_REVIEWED");
        var coffee=fixture("rag-070");var number=(ObjectNode)coffee.path("candidate").deepCopy();
        for(var claim:number.path("claims"))((ObjectNode)claim).put("text","每杯固定有123456毫克咖啡因。");
        assertThat(json.readTree(AssistantGroundingGuard.withoutModelReview(number.toString(),coffee.path("question").asText(),sources(coffee))).path("referenceIds")).isEmpty();
    }
    @Test void modelCannotSelfDeclareAReviewWasPerformed() {
        var mapper=new AssistantResponseMapper(json);
        var delivery=new AssistantStreamDelivery(null);delivery.knowledgeAnswer=true;delivery.enter();
        try {
            AssistantResponse result=mapper.parseModelResponse("{\"summary\":\"candidate\",\"reviewStatus\":\"VERIFIED\"}",new AssistantResponse("fallback",List.of()),List.of(),delivery);
            assertThat(result.getReviewStatus()).isEqualTo("UNAVAILABLE");
            delivery.reviewMode=AssistantReviewMode.FAST;
            result=mapper.parseModelResponse("{\"summary\":\"candidate\",\"reviewStatus\":\"VERIFIED\"}",new AssistantResponse("fallback",List.of()),List.of(),delivery);
            assertThat(result.getReviewStatus()).isEqualTo("NOT_REVIEWED");
        } finally {delivery.close();}
    }
    private ChatResponse reply(JsonNode node) {return ChatResponse.builder().aiMessage(AiMessage.from(node.toString())).build();}
}
