package com.example.mealcheck.service;

import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.AssistantSkillRegistry;
import com.example.mealcheck.service.skill.KeywordAssistantSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSkillRegistryTest {
    @Test
    void selectsHighestPriorityMatchingSkillAndFallsBack() {
        KeywordAssistantSkill fatLoss = new KeywordAssistantSkill(
                new AssistantSkillPlan("fat-loss", "减脂", "FAT_LOSS", "减脂", "减脂策略"),
                List.of("减脂"), 10, false);
        KeywordAssistantSkill general = new KeywordAssistantSkill(
                new AssistantSkillPlan("balanced-diet", "均衡", "GENERAL", "均衡", "均衡策略"),
                List.of(), 0, true);
        AssistantSkillRegistry registry = new AssistantSkillRegistry(List.of(general, fatLoss));

        assertThat(registry.select("我想减脂").skillName()).isEqualTo("fat-loss");
        assertThat(registry.select("今天吃什么").skillName()).isEqualTo("balanced-diet");
        assertThat(registry.descriptors()).extracting(AssistantSkillRegistry.SkillDescriptor::name)
                .containsExactly("fat-loss", "balanced-diet");
    }
}
