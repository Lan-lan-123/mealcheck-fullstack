package com.example.mealcheck.service.skill;

public record AssistantSkillPlan(String skillName,
                                 String description,
                                 String intentCode,
                                 String ragKeywords,
                                 String promptInstruction) {
}
