package com.example.mealcheck.service.weeklyagent;

import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class WeeklySafetyReviewAgent {
    private static final List<String> FORBIDDEN_CLAIMS = List.of(
            "停药", "治愈", "确诊", "保证瘦", "绝食", "只喝水", "替代医生", "无需就医");

    private final WeeklyReportAgentLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public WeeklySafetyReviewAgent(WeeklyReportAgentLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public WeeklyReportReview review(WeeklyReportResponse report,
                                     String profileContext,
                                     WeeklyTrendAnalysis trend,
                                     WeeklyReportDraft draft) {
        List<String> hardRuleIssues = hardRuleIssues(draft);
        if (!hardRuleIssues.isEmpty()) {
            return new WeeklyReportReview(false,
                    "请移除不安全或不完整的内容后重新生成。", hardRuleIssues, false);
        }
        if (report.getTotalMeals() == 0) {
            return new WeeklyReportReview(true, "记录不足，保留规则降级结果。", List.of(), false);
        }

        String prompt = """
                你是 MealCheck 的安全与一致性审查 Agent。检查草稿是否：
                1. 与统计和趋势一致；2. 没有虚构证据；3. 不包含疾病诊断、停药或极端节食；
                4. 建议具体且不过度承诺；5. 与用户目标和画像不冲突。
                只返回严格 JSON：
                {
                  "approved": true,
                  "feedback": "通过原因或具体修订要求",
                  "issues": ["问题"]
                }

                基础统计：%s
                趋势分析：%s
                用户画像：%s
                待审查草稿：%s
                """.formatted(
                Jsons.toJson(objectMapper, report),
                Jsons.toJson(objectMapper, trend),
                profileContext == null ? "暂无" : profileContext,
                Jsons.toJson(objectMapper, draft));

        return llmClient.complete("weekly-report-review-agent", prompt, 0.1)
                .map(this::parse)
                .orElseGet(() -> new WeeklyReportReview(true,
                        "LLM 审查不可用，已通过确定性安全规则。", List.of(), false));
    }

    private WeeklyReportReview parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(Jsons.extractJsonObject(raw));
            if (!root.has("approved")) {
                return new WeeklyReportReview(false, "审查结果缺少 approved 字段。", List.of("审查输出不完整"), true);
            }
            boolean approved = root.path("approved").asBoolean(false);
            String feedback = root.path("feedback").asText("").trim();
            List<String> issues = strings(root.path("issues"), 6);
            if (!approved && feedback.isBlank()) {
                feedback = issues.isEmpty() ? "请修订后重新审查。" : String.join("；", issues);
            }
            return new WeeklyReportReview(approved, feedback, issues, true);
        } catch (Exception ignored) {
            return new WeeklyReportReview(false, "审查输出无法解析，请重新生成。", List.of("审查输出格式错误"), true);
        }
    }

    private List<String> hardRuleIssues(WeeklyReportDraft draft) {
        List<String> issues = new ArrayList<>();
        if (draft == null || draft.reportText().isBlank()) issues.add("周报正文为空");
        if (draft == null || draft.highlights().isEmpty()) issues.add("本周重点为空");
        if (draft == null || draft.nextWeekSuggestions().isEmpty()) issues.add("下周建议为空");
        if (draft != null && draft.nextWeekSuggestions().size() > 6) issues.add("下周建议数量过多");

        String content = draft == null ? "" : (draft.reportText() + " "
                + String.join(" ", draft.highlights()) + " "
                + String.join(" ", draft.nextWeekSuggestions())).toLowerCase(Locale.ROOT);
        FORBIDDEN_CLAIMS.stream()
                .filter(content::contains)
                .map(claim -> "包含不安全表述：" + claim)
                .forEach(issues::add);
        return List.copyOf(issues);
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
}
