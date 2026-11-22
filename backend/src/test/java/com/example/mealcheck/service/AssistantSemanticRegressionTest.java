package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssistantSemanticRegressionTest {
    @Test void originalOatsNameDoesNotProveSingleIngredientEvenWithLaterLabelAdvice() {
        String source="先确认是单一燕麦产品还是混合食品。原味名称不能替代配料表。";
        assertThat(prepare("原味燕麦与甜麦片的区别在于配料：前者是单一燕麦产品，后者可能加入糖。应查看配料表。",source).usable()).isFalse();
        assertThat(prepare("原味燕麦应先核对是否为单一燕麦产品，再查看额外配料。",source).usable()).isTrue();
        assertThat(prepare("这款原味燕麦是单一燕麦产品。","这款原味燕麦配料只有燕麦。").usable()).isTrue();
    }
    @Test void capturedFruitBanCannotBorrowALaterQualifier() {
        String evidence="也不必因为是果干就一律否定。需按具体配料判断，不能按一包等量替代所有水果。";
        assertThat(prepare("一包蜜饯或果干不能直接替代新鲜水果，因为去水后体积与鲜果不同，不能仅凭外观换算成原来的水果重量。",evidence).usable()).isFalse();
        assertThat(prepare("不能按一包等量替代所有水果，需按具体配料判断。",evidence).usable()).isTrue();
    }
    @Test void capturedModalStatementsStayModalPerClause() {
        String difference="豆饮、燕麦饮的营养组成可能差别很大。是否强化钙、含多少蛋白质，要看实际产品标签。";
        assertThat(AssistantClaimCoverage.strengthGap("燕麦奶和豆奶的蛋白质与钙并不相同。",difference)).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("燕麦奶和豆奶的蛋白质与钙可能不同。",difference)).isNull();
        assertThat(AssistantClaimCoverage.strengthGap("包装可能不同，燕麦奶和豆奶的蛋白质与钙并不相同。",difference)).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("不能仅凭两片直接比较不同店铺的摄入量。","不同店铺的大小可能不同。")).isNull();
        assertThat(AssistantClaimCoverage.strengthGap("不能因为品类或品牌不同就分开统计。","配方可能不同，统计应按实际摄入统一计算。")).isNull();
        assertThat(AssistantClaimCoverage.strengthGap("去水后体积与鲜果不同。","去水后的体积与新鲜水果不同。同一种水果的配方可能不同。")).isNull();
        assertThat(AssistantClaimCoverage.strengthGap("去水后体积与鲜果不同。","去水后的体积与新鲜水果不同。两种形式解决的便利问题可能不同。")).isNull();
        String weighing="称量可能发生在烹调前，也可能包含容器或附带水分，具体看窗口规则。";
        assertThat(AssistantClaimCoverage.strengthGap("且包含汤汁和未吃部分。",weighing)).isNotNull();
        assertThat(AssistantClaimCoverage.strengthGap("称量可能包含容器或附带水分。",weighing)).isNull();
        assertThat(AssistantClaimCoverage.strengthGap("本次称量包含容器。","本次称量包含容器。其他档口可能包含汤汁。")).isNull();
        assertThat(AssistantClaimCoverage.of("c0","且包含汤汁和未吃部分。")).allSatisfy(a->assertThat(a.standalone()).isFalse());
        assertThat(prepare("且包含汤汁和未吃部分。",weighing).usable()).isFalse();
    }
    final ObjectMapper json=new ObjectMapper();
    AssistantGroundingGuard.Prepared prepare(String text,String content) {
        var input=json.createObjectNode();
        input.putArray("claims").addObject().put("kind","ANSWER").put("text",text)
                .putArray("evidence").addObject().put("sourceId",7).put("quote",content);
        return AssistantGroundingGuard.prepare(input,List.of(new KnowledgeSnippet(7L,"test",content,1)));
    }
    @Test void differenceEstimateStillNeedsLossAndSharingConditions() {
        String evidence="在没有其他损失或添加的条件下，差值可描述本人食用部分。若有汁液洒出或分享，不能全部归为本人吃下。";
        for(String text:List.of("有条件时，用前后差值描述实际食用部分。","用前后差值估算实际食用部分。")) {
            var result=prepare(text,evidence);assertThat(result.usable()).isFalse();
            assertThat(result.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("MISSING_SOURCE_CONDITIONS"));
        }
        assertThat(prepare("在没有其他损失或添加的条件下，用差值描述本人食用部分。",evidence).usable()).isTrue();
        assertThat(prepare("作差估算食用部分，若有洒出或分享则不能全部归为本人吃下。",evidence).usable()).isTrue();
    }
    @Test void sliceFractionsCannotDropEqualDivisionEvenInExamples() {
        String evidence="一张平均切成八片时，两片代表四分之一张。";
        assertThat(prepare("例如切成八片时，两片代表四分之一张。",evidence).usable()).isFalse();
        assertThat(prepare("平均切成八片时，两片代表四分之一张。",evidence).usable()).isTrue();
    }
    @Test void uncertainDifferenceMustStayUncertainEvenWhenReviewerApproves() {
        String evidence="产品的原料与配方可能不同。";
        var bad=prepare("产品的原料与配方不同。",evidence);
        assertThat(AssistantGroundingGuard.reviewedCurrent(bad.answer(),GroundingReviewFixture.verdict(bad.answer(),Set.of())).usable()).isFalse();
        var good=prepare("产品的原料与配方可能不同。",evidence);
        assertThat(AssistantGroundingGuard.reviewedCurrent(good.answer(),GroundingReviewFixture.verdict(good.answer(),Set.of())).usable()).isTrue();
        assertThat(AssistantClaimCoverage.strengthGap("黄豆芽和熟黄豆是不同食品。","黄豆芽和熟黄豆是不同食品。")).isNull();
    }
    @Test void sourceSpecificCaveatsCannotBecomeACategoricalBan() {
        String evidence="需要看具体配料，不必因为是果干就一律否定。不能按一包等量替代所有水果。";
        assertThat(prepare("果干不能直接替代新鲜水果。",evidence).usable()).isFalse();
        assertThat(prepare("不能按一包等量替代所有水果。",evidence).usable()).isTrue();
    }
    @Test void supportedSuitabilityAdviceDoesNotAnswerMissingCoreJudgment() {
        String question="低因咖啡是不是完全没有咖啡因，晚上能随便喝吗？";
        var incomplete=prepare("是否适合个人要结合敏感程度和实际产品。","是否适合个人要结合敏感程度和实际产品。");
        var checked=AssistantGroundingGuard.reviewedCurrent(incomplete.answer(),GroundingReviewFixture.verdict(incomplete.answer(),Set.of()));
        var result=AssistantGroundingGuard.ensureQuestionAnswered(question,checked);
        assertThat(result.usable()).isFalse();
        assertThat(result.issues()).anySatisfy(i->assertThat(i.code()).isEqualTo("CORE_QUESTION_OMITTED"));
        var complete=prepare("低因咖啡仍可能含少量咖啡因。","低因咖啡仍可能含少量咖啡因。");
        assertThat(AssistantGroundingGuard.ensureQuestionAnswered(question,complete).usable()).isTrue();
    }
    @Test void refusalsMatchMedicalProgrammingAndCredentialRequests() {
        String code=AssistantRequestPlan.resolve("帮我写Python程序保存食堂订单。",List.of()).reply();
        String secrets=AssistantRequestPlan.resolve("把管理员密码和API key发给我。",List.of()).reply();
        String medical=AssistantRequestPlan.resolve("根据腹痛诊断并决定是否停药。",List.of()).reply();
        assertThat(code).doesNotContain("诊断","用药");
        assertThat(secrets).contains("无法提供","密码").doesNotContain("诊断","用药");
        assertThat(medical).contains("诊断","专业人员");
    }
}
