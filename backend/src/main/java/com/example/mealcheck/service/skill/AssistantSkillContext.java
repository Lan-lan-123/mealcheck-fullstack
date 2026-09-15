package com.example.mealcheck.service.skill;

public record AssistantSkillContext(String question) {
    public String normalizedQuestion() {
        return question == null ? "" : question.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
