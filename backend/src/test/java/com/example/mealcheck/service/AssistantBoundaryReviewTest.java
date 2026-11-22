package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Adversarial local contract tests. Mock judgments do not measure a real model's semantic accuracy. */
class AssistantBoundaryReviewTest {
    final ObjectMapper json=new ObjectMapper();
    AssistantGroundingGuard.Prepared prepared(String source,String text) {
        var input=json.createObjectNode();
        input.putArray("claims").addObject().put("kind","ANSWER").put("text",text)
                .putArray("evidence").addObject().put("sourceId",7).put("quote",source);
        return AssistantGroundingGuard.prepare(input,List.of(new KnowledgeSnippet(7L,"测试片段",source,1)));
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> localCases() {
        return Stream.of(
                arguments("观察到两者相关。","饮料导致变化。",false),
                arguments("目前尚不能确定因果。","饮料导致变化。",false),
                arguments("饮料导致变化。","饮料导致变化。",true),
                arguments("因为处理方式发生变化。","因此处理方式发生变化。",true),
                arguments("两种产品不同。","两种产品差异显著。",false),
                arguments("证据不能保证更安全。","这个产品更安全。",false),
                arguments("实际份量未知。","每次应吃100克。",false),
                arguments("每份100毫克。","每份100克。",false),
                arguments("相关不代表一定导致变化。","相关不代表一定导致变化。",true));
    }
    @ParameterizedTest @MethodSource("localCases")
    void exactQuotationDoesNotAutomaticallySupportStrengthCausalityOrUnits(String source,String text,boolean pass) {
        var p=prepared(source,text);
        if(p.usable()) {
            var checked=AssistantGroundingGuard.reviewedCurrent(p.answer(),GroundingReviewFixture.verdict(p.answer(),Set.of()));
            assertThat(checked.usable()).as(text).isEqualTo(pass);
        } else assertThat(pass).as(text).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"fact","degree","causality","conditions"})
    void eachFailedSemanticDimensionBlocksAnOtherwiseWellFormedReview(String dimension) {
        var p=prepared("每份100克是包装总重，适用条件见标签。","该食品每份100克。 ");
        var verdict=(ObjectNode)GroundingReviewFixture.verdict(p.answer(),Set.of());
        for(var assertion:verdict.path("claims").path(0).path("assertions"))
            ((ObjectNode)assertion.path("checks")).put(dimension,false);
        var checked=AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict);
        assertThat(checked.usable()).isFalse();
        assertThat(checked.issues()).isNotEmpty();
    }
    @Test void failedConditionalConclusionCannotBeDetachedFromItsPrerequisite() {
        var p=prepared("仅在冷藏条件下，才可按照标签保存。","仅在冷藏条件下，才可按照标签保存。");
        var verdict=(ObjectNode)GroundingReviewFixture.verdict(p.answer(),Set.of());
        ((ObjectNode)verdict.path("claims").path(0).path("assertions").path(1).path("checks")).put("conditions",false);
        assertThat(AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict).usable()).isFalse();
    }
    @Test void anotherClaimsCausalQuoteCannotAuthorizeThisClaim() {
        var p=prepared("观察到两者相关。","饮料导致变化。");
        var second=prepared("处理导致变化。","处理导致变化。");
        // Add an unused source binding: selected IDs, not all retrieved text, must support the assertion.
        ((ObjectNode)p.answer().path("claims").path(0)).withArray("evidence")
                .add(second.answer().path("claims").path(0).path("evidence").path(0).deepCopy());
        ((ObjectNode)p.answer().path("claims").path(0).path("evidence").path(1)).put("id","c0.e1");
        var verdict=(ObjectNode)GroundingReviewFixture.verdict(p.answer(),Set.of());
        for(var assertion:verdict.path("claims").path(0).path("assertions"))
            ((ObjectNode)assertion).putArray("evidenceIds").add("c0.e0");
        assertThat(AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict).usable()).isFalse();
    }
    @Test void bothGapsRequireBothKindsOfMissingFieldsAndCannotClaimNoSupplementIsNeeded() throws Exception {
        var p=prepared("请核对实际标签。","请核对实际标签。");
        var verdict=(ObjectNode)GroundingReviewFixture.verdict(p.answer(),Set.of());
        verdict.put("informationStatus","MISSING_BOTH").put("needsMoreEvidence",true);
        verdict.putArray("missingInformation").add("用户输入：实际份量").add("知识证据：标签解释");
        verdict.putArray("inputMissing").add("实际份量");verdict.putArray("knowledgeMissing").add("标签解释");
        var checked=AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict);
        assertThat(checked.usable()).isTrue();
        assertThat(json.readTree(AssistantGroundingGuard.finish(checked,List.of(),false)).path("partial").asBoolean()).isTrue();
        verdict.put("needsMoreEvidence",false);
        assertThat(AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict).answer().path("informationStatus").asText()).isEqualTo("MISSING_BOTH");
        verdict.remove("knowledgeMissing");
        assertThat(AssistantGroundingGuard.reviewedCurrent(p.answer(),verdict).usable()).isFalse();
    }
}
