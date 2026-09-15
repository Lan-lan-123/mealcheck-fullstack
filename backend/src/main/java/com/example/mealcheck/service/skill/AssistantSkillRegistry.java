package com.example.mealcheck.service.skill;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class AssistantSkillRegistry {
    private final List<AssistantSkill> skills;

    public AssistantSkillRegistry(List<AssistantSkill> skills) {
        this.skills = skills.stream()
                .sorted(Comparator.comparingInt(AssistantSkill::priority).reversed())
                .toList();
    }

    public AssistantSkillPlan select(String question) {
        AssistantSkillContext context = new AssistantSkillContext(question);
        return skills.stream()
                .filter(skill -> skill.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No fallback assistant skill is registered"))
                .plan(context);
    }

    public List<SkillDescriptor> descriptors() {
        return skills.stream()
                .map(skill -> new SkillDescriptor(skill.name(), skill.description(), skill.priority()))
                .toList();
    }

    public record SkillDescriptor(String name, String description, int priority) {}
}
