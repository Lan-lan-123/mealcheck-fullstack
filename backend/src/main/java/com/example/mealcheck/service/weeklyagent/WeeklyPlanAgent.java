package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WeeklyPlanAgent {
    private final WeeklyReportAgentLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public WeeklyPlanAgent(WeeklyReportAgentLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public WeeklyReportDraft createPlan(WeeklyReportResponse report,
                                        String profileContext,
                                        WeeklyTrendAnalysis trend,
                                        List<KnowledgeSnippet> evidence,
                                        String reviewFeedback) {
        WeeklyReportDraft fallback = fallback(report);
        if (report.getTotalMeals() == 0) {
            return fallback;
        }

        String prompt = """
                你是 MealCheck 的饮食教练 Agent。根据趋势、用户目标和 RAG 证据生成下周行动计划。
                建议必须具体、温和、可执行，不做疾病诊断，不建议停药、极端节食或保证效果。
                不得把 RAG 中没有出现的医学结论当作事实。
                只返回严格 JSON：
                {
                  "reportText": "一段面向用户的周报总结",
                  "highlights": ["2 到 4 条本周重点"],
                  "nextWeekSuggestions": ["3 到 5 条下周行动建议"]
                }

                基础统计：%s
                趋势分析：%s
                用户画像：%s
                RAG 证据：%s
                上一次审查意见：%s
                """.formatted(
                Jsons.toJson(objectMapper, report),
                Jsons.toJson(objectMapper, trend),
                safe(profileContext),
                evidenceText(evidence),
                reviewFeedback == null || reviewFeedback.isBlank() ? "无，这是初稿。" : reviewFeedback);

        return llmClient.complete("weekly-report-plan-agent", prompt, 0.35)
                .map(raw -> parse(raw, fallback))
                .orElse(fallback);
    }

    private WeeklyReportDraft parse(String raw, WeeklyReportDraft fallback) {
        try {
            JsonNode root = objectMapper.readTree(Jsons.extractJsonObject(raw));
            String reportText = root.path("reportText").asText("").trim();
            List<String> highlights = strings(root.path("highlights"), 4);
            List<String> suggestions = strings(root.path("nextWeekSuggestions"), 5);
            if (reportText.isBlank() || highlights.isEmpty() || suggestions.isEmpty()) {
                return fallback;
            }
            return new WeeklyReportDraft(reportText, highlights, suggestions, true);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private WeeklyReportDraft fallback(WeeklyReportResponse report) {
        return new WeeklyReportDraft(
                report.getReportText(),
                report.getHighlights() == null ? List.of() : report.getHighlights(),
                report.getNextWeekSuggestions() == null ? List.of() : report.getNextWeekSuggestions(),
                false);
    }

    private String evidenceText(List<KnowledgeSnippet> evidence) {
        if (evidence == null || evidence.isEmpty()) return "没有匹配到知识片段。";
        return evidence.stream()
                .limit(5)
                .map(item -> "- [" + safe(item.getTitle()) + "] " + truncate(item.getContent(), 500))
                .collect(Collectors.joining("\n"));
    }

    private List<String> strings(JsonNode node, int limit) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank() && values.size() < limit) values.add(value);
        });
        return List.copyOf(values);
    }

    private String truncate(String value, int limit) {
        String safe = safe(value);
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "...";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "暂无" : value.trim();
    }
}
