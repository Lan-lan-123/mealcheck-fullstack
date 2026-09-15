package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class WeeklyTrendAgent {
    private final WeeklyReportAgentLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public WeeklyTrendAgent(WeeklyReportAgentLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public WeeklyTrendAnalysis analyze(WeeklyReportResponse report, String profileContext) {
        WeeklyTrendAnalysis fallback = fallback(report);
        if (report.getTotalMeals() == 0) {
            return fallback;
        }

        String prompt = """
                你是 MealCheck 的饮食趋势分析 Agent。只分析给定数据，不做疾病诊断，不编造记录。
                找出最近周期内最重要的 2 到 4 个趋势，并给出供知识检索使用的关键词。
                只返回严格 JSON：
                {
                  "summary": "一段有数据依据的趋势总结",
                  "findings": ["趋势或问题"],
                  "focusKeywords": ["营养检索关键词"]
                }

                周报聚合数据：
                %s

                长期用户画像：
                %s
                """.formatted(Jsons.toJson(objectMapper, report), safe(profileContext));

        return llmClient.complete("weekly-report-trend-agent", prompt, 0.2)
                .map(raw -> parse(raw, fallback))
                .orElse(fallback);
    }

    private WeeklyTrendAnalysis parse(String raw, WeeklyTrendAnalysis fallback) {
        try {
            JsonNode root = objectMapper.readTree(Jsons.extractJsonObject(raw));
            String summary = root.path("summary").asText("").trim();
            List<String> findings = strings(root.path("findings"), 4);
            List<String> keywords = strings(root.path("focusKeywords"), 8);
            if (summary.isBlank() || findings.isEmpty()) {
                return fallback;
            }
            return new WeeklyTrendAnalysis(summary, findings,
                    keywords.isEmpty() ? fallback.focusKeywords() : keywords, true);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private WeeklyTrendAnalysis fallback(WeeklyReportResponse report) {
        List<String> findings = report.getHighlights() == null
                ? List.of()
                : report.getHighlights().stream().filter(value -> value != null && !value.isBlank()).limit(4).toList();
        if (findings.isEmpty()) {
            findings = List.of(report.getTotalMeals() == 0
                    ? "当前记录不足，暂时无法判断稳定趋势。"
                    : "当前聚合数据未显示额外趋势。"
            );
        }

        List<String> keywords = new ArrayList<>();
        keywords.add(report.getGoalType() == null ? "均衡饮食" : report.getGoalType());
        report.getRiskTotals().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(Map.Entry::getKey)
                .forEach(keywords::add);
        if (report.getTotalMeals() > report.getCategoryTotals().getOrDefault("vegetable", 0)) {
            keywords.add("蔬菜摄入");
        }
        if (report.getTotalMeals() > report.getCategoryTotals().getOrDefault("protein", 0)) {
            keywords.add("蛋白质摄入");
        }
        return new WeeklyTrendAnalysis(report.getReportText(), findings, keywords, false);
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

    private String safe(String value) {
        return value == null || value.isBlank() ? "暂无长期画像。" : value;
    }
}
