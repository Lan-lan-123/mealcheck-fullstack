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
    public boolean supports(AssistantSkillContext context) {
        if (fallback) {
            return true;
        }
        String question = context.normalizedQuestion();
        return keywords.stream().anyMatch(question::contains);
    }

    @Override
    public AssistantSkillPlan plan(AssistantSkillContext context) {
        return plan;
    }
}
