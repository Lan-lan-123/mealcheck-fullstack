package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.RagChainReport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Source identity and explicit relevance labels are reproducible rules, not an entailment judge. */
final class RagEvidenceLabels {
    private static final MarkdownKnowledgeChunker IDENTITY = new MarkdownKnowledgeChunker(new AppProperties());
    private RagEvidenceLabels() { }
    static String contentId(String title, String content) { return IDENTITY.contentHash(title, content); }
    static String normalize(String text) { return text == null ? "" : text.trim().replaceAll("\\s+", " "); }

    static int grade(RagChainReport.Hit hit, JsonNode targets) {
        if (hit.content() == null || hit.knowledgeId() == null) return 0;
        String actual = contentId(hit.title(), hit.content());
        if (!actual.equals(hit.knowledgeId())) return 0;
        for (var target : targets) {
            if (!actual.equals(target.path("knowledgeId").asText())) continue;
            String content = normalize(hit.content());
            for (var quote : target.path("evidenceQuotes")) if (!content.contains(normalize(quote.asText()))) return 0;
            return target.path("relevance").asInt();
        }
        return 0;
    }

    static String judgment(RagChainReport.Hit hit, JsonNode expected) {
        if (hit.title() == null || hit.content() == null || hit.knowledgeId() == null
                || !contentId(hit.title(), hit.content()).equals(hit.knowledgeId())) return "INVALID_CONTENT";
        for (var target : expected.path("evidenceTargets")) {
            if (!hit.knowledgeId().equals(target.path("knowledgeId").asText())) continue;
            if (grade(hit, expected.path("evidenceTargets")) == 0) return "INVALID_CONTENT";
            return target.path("relevance").asInt() == 2 ? "DIRECT" : "BACKGROUND";
        }
        for (var target : expected.path("irrelevantEvidence"))
            if (hit.knowledgeId().equals(target.path("knowledgeId").asText())) return "IRRELEVANT";
        return "UNJUDGED";
    }

    static void validate(JsonNode row, List<PgVectorKnowledgeService.KnowledgeChunk> chunks) {
        String id = row.path("id").asText();
        String kind = row.path("answerability").asText();
        boolean outside = row.path("expectNoAnswer").asBoolean();
        if (!Set.of("ANSWERABLE", "INSUFFICIENT_EVIDENCE", "OUT_OF_SCOPE").contains(kind)
                || outside != "OUT_OF_SCOPE".equals(kind)) fail(id, "answerability 与库外标记不一致");
        if (!row.path("evidenceTargets").isArray() || !row.path("requiredEvidenceGroups").isArray())
            fail(id, "缺少证据目标与覆盖组");
        if (row.path("annotation").path("status").asText().isBlank()
                || !row.path("annotation").path("independentlyAnnotated").isBoolean()) fail(id, "缺少标注来源状态");
        if (row.path("annotation").path("independentlyAnnotated").asBoolean()
                && (!"HUMAN_APPROVED".equals(row.path("annotation").path("status").asText())
                || row.path("annotation").path("reviewer").asText().isBlank())) fail(id, "独立人工标注必须声明审核人和审核状态");
        var targets = row.path("evidenceTargets");
        var groups = row.path("requiredEvidenceGroups");
        if (outside) {
            if (!targets.isEmpty() || !groups.isEmpty() || !row.path("irrelevantEvidence").isEmpty()) fail(id, "库外题不能设置可检索证据");
            return;
        }
        if (targets.isEmpty() || groups.isEmpty()) fail(id, "知识题必须有直接证据");
        var direct = new HashSet<String>();
        var seen = new HashSet<String>();
        for (var target : targets) {
            String key = target.path("knowledgeId").asText();
            int grade = target.path("relevance").asInt(-1);
            if (!seen.add(key) || grade < 1 || grade > 2) fail(id, "重复或无效的相关性等级");
            var source = chunks.stream().filter(chunk -> key.equals(chunk.contentHash())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(id + " 证据正文已变化或不存在，需要重新复核标注"));
            if (!source.title().equals(target.path("title").asText())) fail(id, "证据标题与内容指纹不一致");
            if (!target.path("evidenceQuotes").isArray() || target.path("evidenceQuotes").isEmpty()
                    || target.path("rationale").asText().isBlank()) fail(id, "缺少原文锚点或相关性说明");
            for (var quote : target.path("evidenceQuotes"))
                if (!quote.isTextual() || quote.asText().isBlank()
                        || !normalize(source.content()).contains(normalize(quote.asText()))) fail(id, "原文锚点不在绑定正文中");
            if (grade == 2) direct.add(key);
        }
        for (var target : row.path("irrelevantEvidence")) {
            String key = target.path("knowledgeId").asText();
            if (!seen.add(key) || target.path("relevance").asInt(-1) != 0)
                fail(id, "无关证据标注重复或等级无效");
            var source = chunks.stream().filter(chunk -> key.equals(chunk.contentHash())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(id + " 无关证据正文已变化，需要重新复核"));
            if (!source.title().equals(target.path("title").asText()) || target.path("rationale").asText().isBlank()
                    || !target.path("evidenceQuotes").isArray() || target.path("evidenceQuotes").isEmpty())
                fail(id, "无关证据缺少标题、锚点或说明");
            for (var quote : target.path("evidenceQuotes"))
                if (!quote.isTextual() || quote.asText().isBlank()
                        || !normalize(source.content()).contains(normalize(quote.asText()))) fail(id, "无关证据锚点不在正文中");
        }
        if (direct.isEmpty()) fail(id, "至少需要一个直接证据");
        for (var group : groups) {
            var groupIds = new HashSet<String>();
            if (!group.isArray() || group.isEmpty()) fail(id, "覆盖组不能为空");
            for (var item : group) if (!direct.contains(item.asText()) || !groupIds.add(item.asText()))
                fail(id, "覆盖组只能包含去重后的直接证据 ID");
        }
        if ("INSUFFICIENT_EVIDENCE".equals(kind) && (row.path("boundaryExpectation").path("missingFacts").isEmpty()
                || row.path("boundaryExpectation").path("requiredBehavior").asText().isBlank())) fail(id, "信息不足题缺少边界说明");
    }
    private static void fail(String id, String message) { throw new IllegalArgumentException(id + ": " + message); }
}
