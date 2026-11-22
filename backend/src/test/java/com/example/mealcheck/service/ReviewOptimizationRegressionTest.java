package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReviewOptimizationRegressionTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void objectScopedNegationDoesNotBecomeAnUnsupportedGuarantee() {
        String source="选择应服务自己的实际需要，不能把低因当成必须喝更多杯的理由。";
        for(String text:List.of("不能把低因当成可以随便多喝的保证。","不能将低因咖啡视为适合所有人饮用的保证。",
                "不应把一款产品的名称当作完全无咖啡因的保证。","低因咖啡不保证适合每个人。"))
            assertThat(AssistantClaimCoverage.strengthGap(text,source)).as(text).isNull();
        for(String text:List.of("低因保证可以随便多喝。","不能确认产品，但保证可以随便喝。",
                "不能把低因当成理由，但是它保证适合所有人。","不能把低因当成理由却保证适合所有人。",
                "不能把低因当成理由并保证适合所有人。","不能把低因当成理由也保证适合所有人。",
                "并非不能保证可以随便喝。","不能不保证适合所有人。","不是不保证适合所有人。",
                "不能把低因当成理由。产品保证适合所有人。"))
            assertThat(AssistantClaimCoverage.strengthGap(text,source)).as(text).isNotNull();
    }
    @Test void capturedNegatedGuaranteeSurvivesAllFourChecksWithoutLosingItsClause() throws Exception {
        var source=new KnowledgeSnippet(1L,"低因咖啡","是否适合个人还要结合敏感程度、实际产品和专业建议。选择应服务自己的实际需要，不能把低因当成必须喝更多杯的理由。",1);
        var candidate=json.createObjectNode();var claim=candidate.putArray("claims").addObject().put("kind","ANSWER")
                .put("text","是否适合晚上喝还要结合个人敏感程度和实际产品，不能把低因当成可以随便多喝的保证。");
        claim.putArray("evidence").addObject().put("sourceId",1).putArray("passageIds").add(0).add(1);
        var prepared=AssistantGroundingGuard.prepare(candidate,List.of(source));assertThat(prepared.issues()).isEmpty();
        var checked=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),GroundingReviewFixture.verdict(prepared.answer(),Set.of()));
        assertThat(checked.issues()).isEmpty();assertThat(checked.answer().path("summary").asText()).contains("不能把低因当成可以随便多喝的保证");
    }
    @Test void partialReasonsReachPublicResponseButRecoveredAnswersStayComplete() throws Exception {
        assertThat(json.readTree(AssistantGroundingGuard.insufficient()).path("answerStatus").asText()).isEqualTo("PARTIAL");
        assertThat(json.readTree(AssistantGroundingGuard.safeReply("不提供诊断。",true)).path("answerStatus").asText("COMPLETE")).isEqualTo("COMPLETE");
        var data=json.readTree(getClass().getResourceAsStream("/evaluation/optional_suggestion_budget_capture.json"));
        var sources=new ArrayList<KnowledgeSnippet>();for(var source:data.path("sources"))sources.add(json.treeToValue(source,KnowledgeSnippet.class));
        var prepared=AssistantGroundingGuard.prepare(data.path("candidate"),sources);
        var checked=AssistantGroundingGuard.reviewedCurrent(prepared.answer(),GroundingReviewFixture.verdict(prepared.answer(),Set.of()));
        var mapper=new AssistantResponseMapper(json);
        String raw=AssistantGroundingGuard.finish(checked,prepared.issues(),true);
        AssistantResponse response=mapper.parseModelResponse(raw,new AssistantResponse("fallback",List.of()),List.of(),null);
        assertThat(response.getAnswerStatus()).isEqualTo("PARTIAL");assertThat(response.getAnswerReasonCodes()).containsExactly("OPTIONAL_SUGGESTION_BUDGET");
        assertThat(new AssistantResponse("copy",List.of()).copyOutcomeFrom(response).getAnswerStatus()).isEqualTo("PARTIAL");
        var recovered=json.readTree(AssistantGroundingGuard.finish(checked,prepared.issues(),false));
        assertThat(recovered.path("answerStatus").asText()).isEqualTo("COMPLETE");assertThat(recovered.path("answerReasonCodes")).isEmpty();
        checked.answer().put("informationStatus","MISSING_INPUT");
        var missing=json.readTree(AssistantGroundingGuard.finish(checked,List.of(),false));
        assertThat(missing.path("answerStatus").asText()).isEqualTo("PARTIAL");assertThat(missing.path("answerReasonCodes").toString()).contains("MISSING_INPUT");
    }
    @Test void compactEvidenceIsLosslessAndDoesNotExpandTheBoundQuoteToItsContext() throws Exception {
        var source=new KnowledgeSnippet(1L,"原文","前提需要核对。原文限定不能省略。前后条件必须保留。",1);
        var root=json.createObjectNode();var claims=root.putArray("claims");
        for(String text:List.of("先核对前提。","应保留限定。")) {
            var c=claims.addObject().put("kind","ANSWER").put("text",text);
            c.putArray("evidence").addObject().put("sourceId",1).put("quote","原文限定不能省略。");
        }
        var prepared=AssistantGroundingGuard.prepare(root,List.of(source));assertThat(prepared.issues()).isEmpty();
        String before=prepared.answer().toString();String prompt=AssistantGroundingGuard.reviewPrompt("怎么核对？",prepared.answer(),List.of(source));
        int start=prompt.indexOf("\n回答："),end=prompt.indexOf("\n来源：",start);
        var compact=json.readTree(prompt.substring(start+4,end));var context=json.readTree(prompt.substring(end+4).strip());
        assertThat(compact.path("quoteTexts")).hasSize(1);assertThat(compact.path("quoteTexts").path(0).path("text").asText()).isEqualTo("原文限定不能省略。");
        assertThat(context.path(0).path("context")).hasSize(3);
        assertThat(context.toString()).contains("前提需要核对","前后条件必须保留");
        for(var c:compact.path("claims"))assertThat(c.path("evidence").path(0).path("quoteIds").path(0).asText()).isEqualTo("q0");
        assertThat(prepared.answer().toString()).isEqualTo(before);
        // A legacy substring quote remains the only bound text; surrounding words stay context only.
        ((ObjectNode)root.path("claims").path(0).path("evidence").path(0)).put("quote","限定不能省略");
        var sub=AssistantGroundingGuard.prepare(root,List.of(source));
        String subPrompt=AssistantGroundingGuard.reviewPrompt("怎么核对？",sub.answer(),List.of(source));
        assertThat(subPrompt).contains("\"text\":\"限定不能省略\"","\"text\":\"原文限定不能省略。\"");
    }
}
