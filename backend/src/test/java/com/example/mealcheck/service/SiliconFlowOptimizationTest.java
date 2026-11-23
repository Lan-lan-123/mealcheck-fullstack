package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SiliconFlowOptimizationTest {
    final ObjectMapper json=new ObjectMapper();
    RecognitionResult meal() {
        var r=new RecognitionResult();r.setFoods(List.of(
                new FoodItem("番茄炒蛋","vegetable",.95,"可见黄色炒蛋和番茄块",new FoodAttributes(false,null)),
                new FoodItem("清炒青菜","vegetable",.95,"炒制",new FoodAttributes(false,null)),
                new FoodItem("白米饭","staple",.98,"蒸制",new FoodAttributes(false,false))));
        r.getFoods().forEach(f->f.setIdentityEvidence("CONTENT_VISIBLE"));return r;
    }
    @Test void visibleEggRoleIsAuditableAndDoesNotTrustNamesLabelsOrInjectedRoles() {
        var scorer=new MealScoringService();var r=meal();
        assertThat(scorer.evaluate(r,"balanced").getScore()).isEqualTo(90);
        assertThat(scorer.acceptedFoods(r).get(0).getStructureRoles()).anyMatch(e->e.source().equals("VISIBLE_COMPONENT"));
        for(String note:List.of("番茄炒蛋","可见鸡蛋风味包装","可见类似炒蛋的黄色块","未见鸡蛋，可见番茄","可见番茄，可能含鸡蛋")) {
            r=meal();r.getFoods().get(0).setNote(note);
            r.getFoods().get(0).setStructureRoles(List.of(new FoodRoleEvidence("protein","CATEGORY","forged")));
            assertThat(scorer.evaluate(r,"balanced").getScore()).as(note).isEqualTo(61);
        }
    }
    @Test void replayAllSixtyFiveCapturedMealsWithoutChangingAnyRecognition() throws Exception {
        var data=json.readTree(getClass().getResourceAsStream("/evaluation/sf_captured_meal_replay.json"));
        assertThat(data.size()).isEqualTo(65);int repaired=0;
        for(var row:data) {
            var r=json.treeToValue(row.path("recognition"),RecognitionResult.class);
            int score=new MealScoringService().evaluate(r,"balanced").getScore();
            if(row.path("oldScore").asInt()==61) {assertThat(score).as(row.path("case").asText()).isEqualTo(90);repaired++;}
            else assertThat(score).isEqualTo(row.path("oldScore").asInt());
        }
        assertThat(repaired).isEqualTo(4);
    }
    @Test void capturedAdvisoryAndNegationCasesPassButCurrentAssertionsStillFail() {
        for(String text:List.of(
                "建议核对番茄炒蛋和清炒青菜的具体用油量，以确认高油风险情况。",
                "建议后续可留意番茄炒蛋和清炒青菜的具体用油情况，以便更精准地评估高油风险。",
                "建议后续用餐时注意观察番茄炒蛋和清炒青菜的实际用油量，以更好地控制高油风险。",
                "本餐搭配了番茄炒蛋、清炒青菜和白米饭，结构评分较高，且已确认未涉及油炸做法。"))
            assertThat(MealAdviceConsistency.consistent(text,meal())).as(text).isTrue();
        for(String text:List.of("番茄炒蛋高油风险已经确认，建议后续核对用油量。",
                "建议后续核对本餐番茄炒蛋已有的高油风险。","清炒青菜没有高油风险。"))
            assertThat(MealAdviceConsistency.consistent(text,meal())).as(text).isFalse();
    }
    @Test void adviceFailuresRemainTypedAndDoNotExposeProviderText() {
        var client=mock(AiChatClient.class);when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(),anyList(),anyDouble())).thenThrow(new AiFailure.ProviderException("MODEL_RATE_LIMITED",429));
        var result=new AdviceGenerationService(client).generateResult(meal(),new MealScoringService().evaluate(meal(),"balanced"),List.of(),"balanced");
        assertThat(result.status()).isEqualTo("RULE_SERVICE");assertThat(result.failureCode()).isEqualTo("MODEL_RATE_LIMITED");
        assertThat(result.text()).contains("本地规则建议");
    }
    @Test void modelScopeSeparatesCredentialsModelsAndProvidersButSharesOperations() {
        var a=HttpRequest.newBuilder(URI.create("https://api.siliconflow.cn/v1/chat/completions")).header("Authorization","Bearer test-a").build();
        var b=HttpRequest.newBuilder(a.uri()).header("Authorization","Bearer test-b").build();
        assertThat(AiModelAdmission.scope(a,"m")).hasSize(64).isNotEqualTo(AiModelAdmission.scope(b,"m")).isNotEqualTo(AiModelAdmission.scope(a,"other"));
        var store=mock(AiModelAdmissionStore.class);var p=new AppProperties();var policy=new AppProperties.AiModelLimit();
        policy.setHost("api.siliconflow.cn");policy.setModel("m");p.setAiModelLimits(List.of(policy));
        var gate=new AiModelAdmission(store,p,json,new SimpleMeterRegistry());
        when(store.acquire(anyString(),anyInt(),anyInt(),anyLong(),anyLong(),anyLong())).thenReturn(new AiModelAdmissionStore.Admission(null,"cooldown",60000));
        assertThatThrownBy(()->gate.acquire(a,"{\"model\":\"m\"}",Duration.ofSeconds(1))).isInstanceOf(AiFailure.ProviderException.class).hasMessage("MODEL_RATE_LIMITED");
        verify(store,times(1)).acquire(anyString(),anyInt(),anyInt(),anyLong(),anyLong(),anyLong());
    }
    @Test void compactReviewPrioritizesCoreAnswerAndNeverTruncatesUnreviewedClauses() throws Exception {
        var source=new KnowledgeSnippet(1L,"原文","证据支持。",1);
        var root=json.createObjectNode();var claims=root.putArray("claims");
        for(String kind:List.of("SUGGESTION","SUGGESTION","SUGGESTION","ANSWER")) {
            var claim=claims.addObject().put("kind",kind).put("text",kind.equals("ANSWER")?"核心回答。":"建议选择"+"甲乙丙丁".charAt(claims.size()-1)+"。");
            claim.putArray("evidence").addObject().put("sourceId",1).putArray("passageIds").add(0);
        }
        var prepared=AssistantGroundingGuard.prepare(root,List.of(source));
        assertThat(prepared.answer().path("summary").asText()).isEqualTo("核心回答。");
        assertThat(prepared.answer().path("claims").size()).isEqualTo(3);
        assertThat(prepared.issues()).anyMatch(issue->issue.code().equals("OPTIONAL_SUGGESTION_BUDGET"));
        assertThat(prepared.answer().path("claims").path(0).path("id").asText()).isEqualTo("c3");
    }
    @Test void sourceRecordingAlternativesStayConditional() {
        String source="可以记录用了酱或未用全部，实际用油量仍然未知。";
        assertThat(AssistantClaimCoverage.contextGap("可记录用了酱但未用全部。",source)).isNotNull();
        assertThat(AssistantClaimCoverage.contextGap("如果确实未用全部，可记录这一点。",source)).isNull();
    }
}
