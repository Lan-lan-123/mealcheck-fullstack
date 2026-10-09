package com.example.mealcheck.service.skill;

public interface AssistantSkill {
    String name();

    String description();

    int priority();

    default boolean fallback() {
        return false;
    }

    default int matchScore(AssistantSkillContext context) {
        return supports(context) ? 1 : 0;
    }

    boolean supports(AssistantSkillContext context);

    AssistantSkillPlan plan(AssistantSkillContext context);
}
