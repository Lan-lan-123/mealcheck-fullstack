package com.example.mealcheck.service.skill;

import java.util.List;

public class KeywordAssistantSkill implements AssistantSkill {
    private final AssistantSkillPlan plan;
    private final List<String> keywords;
    private final int priority;
    private final boolean fallback;

    public KeywordAssistantSkill(AssistantSkillPlan plan,
                                 List<String> keywords,
                                 int priority,
                                 boolean fallback) {
        this.plan = plan;
        this.keywords = List.copyOf(keywords);
        this.priority = priority;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return plan.skillName();
    }

    @Override
    public String description() {
        return plan.description();
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean fallback() {
        return fallback;
    }

    @Override
    public int matchScore(AssistantSkillContext context) {
        if (fallback) {
            return 0;
        }
        String question = context.normalizedQuestion();
        return keywords.stream()
                .filter(keyword -> containsNonNegated(question, keyword))
                .mapToInt(keyword -> 1)
                .sum();
    }

    private boolean containsNonNegated(String question, String keyword) {
        int from = 0;
        while (from < question.length()) {
            int index = question.indexOf(keyword, from);
            if (index < 0) {
                return false;
            }
            String prefix = question.substring(Math.max(0, index - 4), index);
            if (!prefix.matches(".*(不|别|不聊|别聊|不想|不再|无需|不用|停止|取消)$")) {
                return true;
            }
            from = index + keyword.length();
        }
        return false;
    }

    @Override
    public boolean supports(AssistantSkillContext context) {
        if (fallback) {
            return true;
        }
        return matchScore(context) > 0;
    }

    @Override
    public AssistantSkillPlan plan(AssistantSkillContext context) {
        return plan;
    }
}
