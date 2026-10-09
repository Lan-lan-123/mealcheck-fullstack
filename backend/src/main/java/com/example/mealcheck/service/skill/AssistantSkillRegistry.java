package com.example.mealcheck.service.skill;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class AssistantSkillRegistry {
    private final List<AssistantSkill> skills;

    public AssistantSkillRegistry(List<AssistantSkill> skills) {
        this.skills = skills.stream()
                .sorted(Comparator.comparingInt(AssistantSkill::priority).reversed())
                .toList();
    }

    public AssistantSkillPlan select(String question) {
        return select(question, List.of());
    }

    public AssistantSkillPlan select(String question, List<String> recentUserMessages) {
        AssistantSkillContext latest = new AssistantSkillContext(question);
        Optional<AssistantSkill> direct = bestMatch(latest);
        if (direct.isPresent()) {
            return direct.get().plan(latest);
        }

        if (isContextDependent(question) && recentUserMessages != null && !recentUserMessages.isEmpty()) {
            String contextualQuestion = recentUserMessages.stream()
                    .filter(message -> message != null && !message.isBlank())
                    .skip(Math.max(0, recentUserMessages.size() - 1L))
                    .reduce("", (left, right) -> left + " " + right)
                    + " " + (question == null ? "" : question);
            AssistantSkillContext contextual = new AssistantSkillContext(contextualQuestion);
            Optional<AssistantSkill> contextualMatch = bestMatch(contextual);
            if (contextualMatch.isPresent()) {
                return contextualMatch.get().plan(contextual);
            }
        }

        return skills.stream()
                .filter(AssistantSkill::fallback)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No fallback assistant skill is registered"))
                .plan(latest);
    }

    private Optional<AssistantSkill> bestMatch(AssistantSkillContext context) {
        return skills.stream()
                .filter(skill -> !skill.fallback())
                .filter(skill -> skill.matchScore(context) > 0)
                .max(Comparator.comparingInt((AssistantSkill skill) -> skill.matchScore(context))
                        .thenComparingInt(AssistantSkill::priority));
    }

    private boolean isContextDependent(String question) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        return normalized.length() <= 10
                || normalized.startsWith("那")
                || normalized.startsWith("这个")
                || normalized.startsWith("那个")
                || normalized.startsWith("它")
                || normalized.contains("刚才")
                || normalized.contains("前面");
    }

    public List<SkillDescriptor> descriptors() {
        return skills.stream()
                .map(skill -> new SkillDescriptor(skill.name(), skill.description(), skill.priority()))
                .toList();
    }

    public record SkillDescriptor(String name, String description, int priority) {}
}
