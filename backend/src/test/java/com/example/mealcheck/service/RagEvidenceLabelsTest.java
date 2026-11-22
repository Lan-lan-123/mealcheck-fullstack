package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.RagChainReport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RagEvidenceLabelsTest {
    final ObjectMapper json = new ObjectMapper();
    List<PgVectorKnowledgeService.KnowledgeChunk> corpus() throws Exception {
        try (var in = new ClassPathResource("knowledge/diet_guides.md").getInputStream()) {
            return new MarkdownKnowledgeChunker(new AppProperties()).split(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    JsonNode dataset() throws Exception {
        try (var in = new ClassPathResource("knowledge/rag_eval_cases.json").getInputStream()) { return json.readTree(in); }
    }
    RagChainReport.Hit hit(String title, String content) {
        return new RagChainReport.Hit(7L, title, "general", .9, RagEvidenceLabels.contentId(title, content), content);
    }
    JsonNode expected(RagChainReport.Hit... hits) {
        var expected = json.createObjectNode();
        var targets = expected.putArray("evidenceTargets");
        var group = expected.putArray("requiredEvidenceGroups").addArray();
        for (var hit : hits) {
            var target = targets.addObject().put("knowledgeId", hit.knowledgeId()).put("title", hit.title()).put("relevance", 2);
            target.putArray("evidenceQuotes").add(hit.content());
            group.add(hit.knowledgeId());
        }
        return expected;
    }
    RagChainReport.Retrieval search(RagChainReport.Hit... hits) {
        return new RagChainReport.Retrieval("call", "实际查询", 5, "SUCCESS", 1, List.of(hits), null);
    }

    @Test void allCasesHaveSourceBoundLabelsAndExplicitPendingReviewStatus() throws Exception {
        var rows = dataset(); var chunks = corpus();
        assertThat(rows.size()).isEqualTo(200);
        var counts = new HashMap<String, Integer>();
        int multiSource = 0;
        for (var row : rows) {
            RagEvidenceLabels.validate(row, chunks);
            counts.merge(row.path("answerability").asText(), 1, Integer::sum);
            assertThat(row.path("annotation").path("independentlyAnnotated").asBoolean()).isFalse();
            assertThat(row.path("annotation").path("status").asText()).isEqualTo("PENDING_HUMAN_REVIEW");
            if (row.path("evidenceTargets").findValues("relevance").stream().filter(value -> value.asInt() == 2).count() > 1) multiSource++;
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("ANSWERABLE", 168, "INSUFFICIENT_EVIDENCE", 12, "OUT_OF_SCOPE", 20));
        assertThat(multiSource).isGreaterThanOrEqualTo(20);
    }

    @Test void labelDriftMissingAnchorsAndFalseHumanFlagsAreRejected() throws Exception {
        var row = (ObjectNode) dataset().get(0).deepCopy(); var chunks = corpus();
        var target = (ObjectNode) row.path("evidenceTargets").get(0);
        String original = target.path("knowledgeId").asText();
        target.put("knowledgeId", "0".repeat(64));
        assertThatThrownBy(() -> RagEvidenceLabels.validate(row, chunks)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("复核");
        target.put("knowledgeId", original);
        target.putArray("evidenceQuotes").add("正文中不存在的证据");
        assertThatThrownBy(() -> RagEvidenceLabels.validate(row, chunks)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("锚点");
        var originalRow = (ObjectNode) dataset().get(0);
        ((ObjectNode) originalRow.path("annotation")).put("independentlyAnnotated", true);
        assertThatThrownBy(() -> RagEvidenceLabels.validate(originalRow, chunks)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("审核人");
    }

    @Test void sameTitleWithWrongContentOrForgedIdentityDoesNotPass() {
        var correct = hit("目标知识", "缺少份量时保留未知。");
        var expected = expected(correct);
        var wrong = hit("目标知识", "与目标无关的正文。");
        var forged = new RagChainReport.Hit(7L, correct.title(), "general", .9, correct.knowledgeId(), wrong.content());
        assertThat(RagChainAssessment.assess(expected, List.of(search(wrong))).retrievalPassed()).isFalse();
        assertThat(RagChainAssessment.assess(expected, List.of(search(forged))).retrievalPassed()).isFalse();
        assertThat(RagChainAssessment.assess(expected, List.of(search(correct))).retrievalPassed()).isTrue();
    }

    @Test void anyDirectAlternativeCanPassAndSupplementKeepsPrimaryMissVisible() {
        var first = hit("直接知识一", "保留未知。"); var alternate = hit("直接知识二", "不能推算。");
        var expected = expected(first, alternate);
        var wrong = hit("背景噪声", "无关内容。");
        var report = RagChainAssessment.assess(expected, List.of(search(wrong), search(alternate)));
        assertThat(report.hitAt5()).isFalse(); assertThat(report.firstRelevantRank()).isZero();
        assertThat(report.finalEvidenceCoverage()).isEqualTo(1.0);
        assertThat(report.retrievalPassed()).isTrue(); assertThat(report.recoveredBySupplement()).isTrue();
    }

    @Test void anActualSiblingChunkCannotPassOnlyBecauseItsTopicTitleMatches() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(5);
        properties.getKnowledge().setChunkMaxTokens(12);
        properties.getKnowledge().setChunkOverlapTokens(1);
        var chunks = new MarkdownKnowledgeChunker(properties).split(
                "<!-- mealcheck:independent-topics -->\n## 同一个长主题\n份量未知时保留未知。\n\n不同餐次可以分别记录具体菜名，不要从餐盘面积推算克数。");
        assertThat(chunks.size()).isGreaterThan(1);
        var core = chunks.get(0); var sibling = chunks.get(chunks.size() - 1);
        assertThat(core.title()).isEqualTo(sibling.title());
        var expected = expected(hit(core.title(), core.content()));
        assertThat(RagChainAssessment.assess(expected, List.of(search(hit(sibling.title(), sibling.content())))).retrievalPassed()).isFalse();
        assertThat(RagChainAssessment.assess(expected, List.of(search(hit(core.title(), core.content())))).retrievalPassed()).isTrue();
    }

    @Test void backgroundEvidenceIsRankedButDoesNotSatisfyDirectEvidenceCoverage() {
        var direct = hit("具体知识", "需要核对每份重量。"); var background = hit("背景知识", "注意标签。");
        var expected = expected(direct, background);
        ((ObjectNode) expected.path("evidenceTargets").get(1)).put("relevance", 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) expected.path("requiredEvidenceGroups").get(0)).remove(1);
        var report = RagChainAssessment.assess(expected, List.of(search(background)));
        assertThat(report.hitAt3()).isTrue();
        assertThat(report.finalEvidenceCovered()).isFalse(); assertThat(report.retrievalPassed()).isFalse();
        double ideal = 3 + 1 / (Math.log(3) / Math.log(2));
        assertThat(report.ndcgAt5()).isCloseTo(1 / ideal, within(.00001));
    }

    @Test void repeatedSourcesCannotInflateNdcgOrMultiGroupCoverage() {
        var first = hit("证据一", "第一条件。"); var second = hit("证据二", "第二条件。");
        var expected = expected(first, second);
        var groups = ((ObjectNode) expected).putArray("requiredEvidenceGroups");
        groups.addArray().add(first.knowledgeId()); groups.addArray().add(second.knowledgeId());
        var duplicate = RagChainAssessment.assess(expected, List.of(search(first, first)));
        assertThat(duplicate.finalEvidenceCoverage()).isEqualTo(.5);
        assertThat(duplicate.retrievalPassed()).isFalse(); assertThat(duplicate.ndcgAt5()).isLessThan(1.0);
        var repaired = RagChainAssessment.assess(expected, List.of(search(first), search(second)));
        assertThat(repaired.finalEvidenceCoverage()).isEqualTo(1.0); assertThat(repaired.recoveredBySupplement()).isTrue();
    }

    @Test void retrievalErrorCannotBeHiddenByAnEarlierHit() {
        var direct = hit("直接知识", "有依据。");
        var error = new RagChainReport.Retrieval("failed", "补充查询", 3, "ERROR", 1, List.of(), "Timeout");
        var report = RagChainAssessment.assess(expected(direct), List.of(search(direct), error));
        assertThat(report.finalEvidenceCovered()).isTrue();
        assertThat(report.retrievalStatus()).isEqualTo("ERROR"); assertThat(report.retrievalPassed()).isFalse();
    }

    @Test void unjudgedSiblingIsPendingWhileExplicitIrrelevanceIsAMiss() {
        var direct = hit("同主题", "必要计量信息。");
        var sibling = hit("同主题", "订单收纳的说明。");
        var expected = (ObjectNode) expected(direct);
        var pending = RagChainAssessment.assess(expected, List.of(search(sibling)));
        assertThat(pending.retrievalStatus()).isEqualTo("UNJUDGED");
        assertThat(pending.retrievalPassed()).isFalse();
        assertThat(pending.judgmentCoverage().primaryRankingComplete()).isFalse();
        assertThat(pending.judgmentCoverage().totalUnjudgedCount()).isEqualTo(1);
        expected.putArray("irrelevantEvidence").addObject()
                .put("knowledgeId", sibling.knowledgeId()).put("relevance", 0);
        var miss = RagChainAssessment.assess(expected, List.of(search(sibling)));
        assertThat(miss.retrievalStatus()).isEqualTo("FAILED");
        assertThat(miss.judgmentCoverage().primaryRankingComplete()).isTrue();
    }

    @Test void knownDirectEvidenceCanPassAlongsideUnknownWithoutPretendingRankingIsComplete() {
        var direct = hit("支持知识", "必要条件。"); var unknown = hit("其他知识", "暂未复核。");
        var report = RagChainAssessment.assess(expected(direct), List.of(search(unknown, direct)));
        assertThat(report.retrievalStatus()).isEqualTo("PASSED");
        assertThat(report.firstRelevantRank()).isEqualTo(2);
        assertThat(report.judgmentCoverage().primaryRankingComplete()).isFalse();
        assertThat(report.judgmentCoverage().totalUnjudgedCount()).isEqualTo(1);
    }

    @Test void malformedIdentityAndExecutionErrorsCannotBeReclassifiedAsMissingAnnotations() {
        var direct = hit("支持知识", "必要条件。");
        var forged = new RagChainReport.Hit(1L, direct.title(), "general", .9, direct.knowledgeId(), "伪造内容。");
        var report = RagChainAssessment.assess(expected(direct), List.of(search(forged)));
        assertThat(report.retrievalStatus()).isEqualTo("FAILED");
        assertThat(report.judgmentCoverage().invalidContentCount()).isEqualTo(1);
        assertThat(report.judgmentCoverage().totalUnjudgedCount()).isZero();
        var unknown = hit("其他", "尚未判断。");
        var error = new RagChainReport.Retrieval("error", "查询", 3, "ERROR", 1, List.of(), "Timeout");
        assertThat(RagChainAssessment.assess(expected(direct), List.of(search(unknown), error)).retrievalStatus()).isEqualTo("ERROR");
    }

    @Test void invalidExplicitIrrelevanceIsRejectedAgainstRealCorpus() throws Exception {
        var row = (ObjectNode) dataset().get(0).deepCopy();
        row.putArray("irrelevantEvidence").add(row.path("evidenceTargets").get(0).deepCopy());
        assertThatThrownBy(() -> RagEvidenceLabels.validate(row, corpus()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无关证据");
    }

    @Test void reviewedCompoundCasesRequireBothFacetsAndGenericPlanningCannotReplaceThem() throws Exception {
        var chunks = corpus();
        for (String caseId : List.of("rag-047", "rag-175", "rag-179")) {
            var row = java.util.stream.StreamSupport.stream(dataset().spliterator(), false)
                    .filter(item -> caseId.equals(item.path("id").asText())).findFirst().orElseThrow();
            var groups = row.path("requiredEvidenceGroups");
            assertThat(groups.size()).as(caseId).isEqualTo(2);
            var left = new HashSet<String>(); groups.get(0).forEach(item -> left.add(item.asText()));
            var right = new HashSet<String>(); groups.get(1).forEach(item -> right.add(item.asText()));
            String first = left.stream().filter(id -> !right.contains(id)).findFirst().orElseThrow();
            String second = right.stream().filter(id -> !left.contains(id)).findFirst().orElseThrow();
            var a = chunks.stream().filter(c -> c.contentHash().equals(first)).findFirst().orElseThrow();
            var b = chunks.stream().filter(c -> c.contentHash().equals(second)).findFirst().orElseThrow();
            var firstHit = hit(a.title(), a.content()); var secondHit = hit(b.title(), b.content());
            assertThat(RagChainAssessment.assess(row, List.of(search(firstHit))).retrievalPassed()).as(caseId).isFalse();
            assertThat(RagChainAssessment.assess(row, List.of(search(secondHit))).retrievalPassed()).as(caseId).isFalse();
            assertThat(RagChainAssessment.assess(row, List.of(search(firstHit, secondHit))).retrievalPassed()).as(caseId).isTrue();
        }
        var question = dataset().get(46);
        var genericTarget = java.util.stream.StreamSupport.stream(question.path("evidenceTargets").spliterator(), false)
                .filter(t -> t.path("title").asText().equals("饮食记录与持续改进") && t.path("relevance").asInt() == 1)
                .findFirst().orElseThrow();
        var plan = chunks.stream().filter(c -> c.contentHash().equals(genericTarget.path("knowledgeId").asText()))
                .findFirst().orElseThrow();
        assertThat(RagChainAssessment.assess(question, List.of(search(hit(plan.title(), plan.content())))).retrievalPassed()).isFalse();
    }
}
