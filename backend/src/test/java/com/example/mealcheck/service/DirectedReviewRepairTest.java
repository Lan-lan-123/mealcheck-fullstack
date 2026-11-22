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

class DirectedReviewRepairTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode cases() throws Exception {return json.readTree(getClass().getResourceAsStream("/evaluation/review_directed_regressions_20261029.json"));}
    JsonNode fixture(String id) throws Exception {for(var c:cases())if(c.path("id").asText().equals(id))return c;throw new IllegalArgumentException(id);}
    List<KnowledgeSnippet> sources(JsonNode c) throws Exception {var result=new ArrayList<KnowledgeSnippet>();for(var s:c.path("sources"))result.add(json.treeToValue(s,KnowledgeSnippet.class));return result;}
    @Test void actualCapturedGeneralAndMissingInputCasesAreIndependentOfKnowledgeAssessment() throws Exception {
        for(var c:cases()) {
            if(c.path("id").asText().equals("rag-187"))continue;
            String question=c.path("question").asText();var prepared=AssistantGroundingGuard.prepare(c.path("candidate"),sources(c));
            assertThat(prepared.issues()).as(c.path("id").asText()).isEmpty();
            var verdict=AssistantQuestionRequirements.normalizeAssessment(question,c.path("verdict"));
            assertThat(verdict.path("knowledgeMissing")).isEqualTo(c.path("verdict").path("knowledgeMissing"));
            var checked=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),verdict);
            assertThat(checked.issues()).as(c.path("id").asText()).isEmpty();
            boolean missing=Set.of("rag-172","rag-184").contains(c.path("id").asText());
            assertThat(checked.answer().path("informationStatus").asText()).isEqualTo(missing?"MISSING_INPUT":"SUFFICIENT");
            if(c.path("id").asText().equals("rag-128"))assertThat(checked.answer().path("summary").asText()).contains("因为秤上读数包含果皮和果核");
            assertThat(c.path("verdict").has("modelInputMissing")).isFalse();
        }
    }
    @Test void gapPolicyPreservesBothGapsPersonalQuestionsProvidedFactsAndMalformedReviews() throws Exception {
        var verdict=json.createObjectNode().put("answersQuestion",true);verdict.putArray("inputMissing").add("本人敏感程度");verdict.putArray("knowledgeMissing").add("核对原则");
        var general=AssistantQuestionRequirements.normalizeAssessment("低因咖啡是不是完全没有咖啡因？",verdict);
        assertThat(general.path("inputMissing")).isEmpty();assertThat(general.path("knowledgeMissing")).hasSize(1);
        assertThat(AssistantQuestionRequirements.normalizeAssessment("我对咖啡因敏感，今晚能喝这杯吗？",verdict).path("inputMissing")).hasSize(1);
        verdict.putArray("inputMissing");
        String missing="沙拉酱已经拌进菜里，没有配方或用量记录，能根据照片确定用了多少油吗？";
        var both=AssistantQuestionRequirements.normalizeAssessment(missing,verdict);
        assertThat(both.path("inputMissing")).hasSize(1);assertThat(both.path("knowledgeMissing")).hasSize(1);
        assertThat(AssistantQuestionRequirements.normalizeAssessment(missing+"现在有完整配方，已补齐实际用量记录。",verdict).path("inputMissing")).isEmpty();
        assertThat(AssistantQuestionRequirements.normalizeAssessment(missing+"已经记录水果重量。",verdict).path("inputMissing")).hasSize(1);
        verdict.putArray("knowledgeMissing").add("原则甲").add("原则乙").add("原则丙").add("原则丁").add("原则戊");
        var overflow=AssistantQuestionRequirements.normalizeAssessment(missing,verdict);
        assertThat(overflow.path("inputMissing")).hasSize(1);
        assertThat(AssistantGroundingGuard.reviewedCurrent(json.createObjectNode(),overflow).issues()).anyMatch(i->i.code().equals("INCOMPLETE_REVIEW"));
        verdict.put("inputMissing","wrong");assertThat(AssistantQuestionRequirements.normalizeAssessment(missing,verdict)).isSameAs(verdict);
        var unrelated=json.createObjectNode().put("answersQuestion",true);unrelated.putArray("inputMissing").add("实际冷藏温度");unrelated.putArray("knowledgeMissing");
        assertThat(AssistantQuestionRequirements.normalizeAssessment("低因咖啡是不是完全没有咖啡因？",unrelated).path("inputMissing")).hasSize(1);
    }
    @Test void compositionExemptionNeverGrantsMissingComponentsNumbersHealthOrExtraCauses() {
        String source="食物称量需区分容器、包装、果皮、果核和真正吃下的部分。";
        assertThat(AssistantClaimCoverage.strengthGap("因为秤上读数包含果皮和果核等不可食部分。",source)).isNull();
        for(String text:List.of("因为秤上读数包含果皮，所以吃下的重量一定相同。","因为果皮导致血糖升高。","因为秤上读数包含毒素。","因为秤上读数包含包装并导致健康风险。"))
            assertThat(AssistantClaimCoverage.strengthGap(text,source)).as(text).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("因为秤上读数包含果核。","食物称量需区分容器和包装。" )).isNotNull();
    }
    @Test void incompleteMultiPartAnswerIsRepairedBeforeReviewWithoutDiscardingConditions() throws Exception {
        var c=fixture("rag-187");var sources=sources(c);String question=c.path("question").asText();
        var first=AssistantGroundingGuard.prepare(c.path("candidate"),sources);
        var rejected=AssistantGroundingGuard.ensureQuestionAnswered(question,first);
        assertThat(rejected.usable()).isFalse();assertThat(rejected.issues()).anyMatch(i->i.code().equals("CORE_QUESTION_OMITTED"));
        var repaired=AssistantGroundingGuard.prepare(c.path("repairCandidate"),sources);
        assertThat(repaired.issues()).isEmpty();assertThat(AssistantGroundingGuard.ensureQuestionAnswered(question,repaired).usable()).isTrue();
        var tools=mock(AssistantFunctionToolRegistry.class);when(tools.specifications()).thenReturn(List.of());
        when(tools.execute(any(),any(),anyLong())).thenReturn(new AssistantFunctionToolRuntime.ToolResult("evidence",sources));
        var factory=mock(AssistantChatModelFactory.class);var generate=mock(ChatModel.class);var review=mock(ChatModel.class);
        when(factory.create(any())).thenReturn(generate);when(factory.create(any(),eq(0.0),eq("diet-assistant-evidence-review"))).thenReturn(review);
        when(generate.chat(any(ChatRequest.class))).thenReturn(reply(c.path("candidate")),reply(c.path("repairCandidate")));
        when(review.chat(any(ChatRequest.class))).thenReturn(reply(GroundingReviewFixture.verdict(repaired.answer(),Set.of())));
        var executor=Executors.newSingleThreadExecutor();
        try {
            String raw=new AssistantFunctionCallingService(new AppProperties(),tools,factory,executor).askWithRuntime("system",List.of(),question,tools).answer();
            var answer=json.readTree(raw);assertThat(answer.path("summary").asText()).contains("保存要求","饭团");
            assertThat(answer.path("answerStatus").asText()).isEqualTo("COMPLETE");
            verify(generate,times(2)).chat(any(ChatRequest.class));verify(review,times(1)).chat(any(ChatRequest.class));verify(tools,times(1)).execute(any(),any(),anyLong());
        } finally {executor.shutdownNow();}
    }
    private ChatResponse reply(JsonNode node) {return ChatResponse.builder().aiMessage(AiMessage.from(node.toString())).build();}
}
