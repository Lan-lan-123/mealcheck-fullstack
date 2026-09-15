package com.example.mealcheck.service.skill;

public interface AssistantSkill {
    String name();

    String description();

    int priority();

    boolean supports(AssistantSkillContext context);

    AssistantSkillPlan plan(AssistantSkillContext context);
}
