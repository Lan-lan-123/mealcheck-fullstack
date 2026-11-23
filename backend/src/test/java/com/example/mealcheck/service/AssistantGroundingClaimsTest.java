package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssistantGroundingClaimsTest {
    final ObjectMapper json=new ObjectMapper();
    final KnowledgeSnippet source=new KnowledgeSnippet(7L,"漏餐","漏餐后不必大量补吃。\r\n\r\n下一餐恢复正常搭配。",1);
    ObjectNode claim(String kind,String text,long id,int passage) {
        var c=json.createObjectNode().put("kind",kind).put("text",text);
        c.putArray("evidence").addObject().put("sourceId",id).putArray("passageIds").add(passage);return c;
    }
    ObjectNode input(ObjectNode... claims) {
        var node=json.createObjectNode();var items=node.putArray("claims");for(var claim:claims)items.add(claim);return node;
    }
    @Test void sourcePassagesKeepExactSpansAndDoNotExposeReferenceFooter() {
        String content=source.getContent()+"\n参考：[网页](https://example.com)。";
        var passages=AssistantEvidencePassages.of(content);assertThat(passages).hasSize(2);
        for(var p:passages)assertThat(content.substring(p.start(),p.end())).isEqualTo(p.text());
        assertThat(AssistantEvidencePassages.exactQuote(content,"漏餐后不必大量补吃。下一餐恢复正常搭配。"))
                .isEqualTo(source.getContent());
        assertThat(AssistantEvidencePassages.exactQuote("不要吃得太多。请正常吃饭。","不要吃。请正常吃饭。")).isNull();
        assertThat(AssistantEvidencePassages.exactQuote("每份10 0克。","每份100克。")).isNull();
    }
    @Test void toolPayloadRetainsTextBudgetAndStableFullSourcePassageIds() throws Exception {
        var longSource=new KnowledgeSnippet(7L,"长文","首句有依据。"+"字".repeat(1300)+"。末句。",1);
        var view=json.readTree(AssistantGroundingGuard.evidencePayload(List.of(longSource))).path("results").path(0);
        assertThat(view.path("content").asText()).hasSize(1200);
        assertThat(view.path("passages")).hasSize(1);
        var prepared=AssistantGroundingGuard.prepare(input(claim("ANSWER","首句有依据。",7,0)),List.of(longSource));
        assertThat(prepared.usable()).isTrue();assertThat(prepared.issues()).isEmpty();
    }
    @Test void derivesAllDisplayFieldsFromClaimsAndActualSourcePassages() {
        var node=input(claim("ANSWER","漏餐后不用大量补吃。",7,0),claim("SUGGESTION","下一餐恢复正常搭配。",7,1));
        node.put("summary","未经绑定的多余文字1000克。");node.putArray("referenceIds").add(999);
        var prepared=AssistantGroundingGuard.prepare(node,List.of(source));
        assertThat(prepared.issues()).isEmpty();assertThat(prepared.usable()).isTrue();
        assertThat(prepared.answer().path("summary").asText()).isEqualTo("漏餐后不用大量补吃。");
        assertThat(prepared.answer().path("referenceIds").toString()).isEqualTo("[7]");
        assertThat(prepared.answer().path("claims").path(0).path("evidence").path(0).path("quotes").path(0).asText())
                .isEqualTo("漏餐后不必大量补吃。");
    }
    @Test void invalidOptionalClaimDoesNotEraseValidAnswerAndReportsExactField() {
        var prepared=AssistantGroundingGuard.prepare(input(claim("ANSWER","不用补吃。",7,0),
                claim("SUGGESTION","每天吃100克。",7,1)),List.of(source));
        assertThat(prepared.usable()).isTrue();assertThat(prepared.answer().path("claims")).hasSize(1);
        assertThat(prepared.issues()).anySatisfy(issue->{
            assertThat(issue.path()).isEqualTo("claims[1].text");assertThat(issue.code()).isEqualTo("UNSUPPORTED_NUMBER");
            assertThat(issue.message()).contains("100克");});
        assertThat(AssistantGroundingGuard.finish(prepared,prepared.issues())).doesNotContain("每天吃100克");
    }
    @Test void unknownIdsSkippedTextAndWrongUnitsAreRejected() throws Exception {
        var numeric=new KnowledgeSnippet(7L,"标签","每份100毫克，每包10份。",1);
        assertThat(AssistantGroundingGuard.prepare(input(claim("ANSWER","每份100克。",7,0)),List.of(numeric)).usable()).isFalse();
        assertThat(AssistantGroundingGuard.prepare(input(claim("ANSWER","不用补吃。",999,0)),List.of(source)).usable()).isFalse();
        assertThat(AssistantGroundingGuard.prepare(input(claim("ANSWER","不用补吃。",7,99)),List.of(source)).usable()).isFalse();
        var legacy=json.readTree("{\"claims\":[{\"text\":\"不用补吃。\",\"sourceId\":7,\"quote\":\"漏餐后...正常搭配。\"}]}");
        assertThat(AssistantGroundingGuard.prepare(legacy,List.of(source)).usable()).isFalse();
    }
    @Test void semanticReviewerRetainsOnlySupportedClaimsAndRecomputesReferences() throws Exception {
        var other=new KnowledgeSnippet(8L,"其他","不要乱推断。",1);
        var prepared=AssistantGroundingGuard.prepare(input(claim("ANSWER","不用补吃。",7,0),claim("SUGGESTION","错误推断。",8,0)),List.of(source,other));
        var verdict=GroundingReviewFixture.verdict(prepared.answer(),Set.of("c1"));
        var checked=AssistantGroundingGuard.reviewed(prepared.answer(),verdict);
        assertThat(checked.usable()).isTrue();assertThat(checked.answer().path("suggestions")).isEmpty();
        assertThat(checked.answer().path("referenceIds").toString()).isEqualTo("[7]");
        assertThat(checked.issues()).singleElement().satisfies(issue->assertThat(issue.message()).isEqualTo("材料不支持"));
        var wrong=json.readTree("{\"answersQuestion\":true,\"claims\":[{\"id\":\"forged\",\"supported\":true}]}");
        assertThat(AssistantGroundingGuard.reviewed(prepared.answer(),wrong).usable()).isFalse();
    }
    @Test void suggestionAloneAndIrrelevantReviewedAnswerCannotBeDelivered() throws Exception {
        assertThat(AssistantGroundingGuard.prepare(input(claim("SUGGESTION","正常吃饭。",7,1)),List.of(source)).usable()).isFalse();
        var prepared=AssistantGroundingGuard.prepare(input(claim("ANSWER","不用补吃。",7,0)),List.of(source));
        assertThat(AssistantGroundingGuard.reviewed(prepared.answer(),json.readTree("{\"answersQuestion\":false,\"supported\":true}")).usable()).isFalse();
    }
    @Test void replaysThreeActualFormattingFailuresWithoutTrustingUnboundDisplayText() throws Exception {
        try(var input=new ClassPathResource("evaluation/rag_grounding_regressions.json").getInputStream()) {
            for(var fixture:json.readTree(input)) {
                var sources=new ArrayList<KnowledgeSnippet>();for(var s:fixture.path("sources"))sources.add(json.treeToValue(s,KnowledgeSnippet.class));
                var prepared=AssistantGroundingGuard.prepare(fixture.path("answer"),sources,6,12);
                assertThat(prepared.usable()).as("case "+fixture.path("caseId")).isTrue();
                assertThat(prepared.issues()).as("case "+fixture.path("caseId")).isEmpty();
            }
        }
    }
}
