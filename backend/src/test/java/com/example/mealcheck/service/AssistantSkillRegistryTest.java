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

    @Test
    void usesRecentUserContextOnlyForAmbiguousFollowUps() {
        KeywordAssistantSkill fried = new KeywordAssistantSkill(
                new AssistantSkillPlan("fried-food-control", "油炸", "FRIED", "油炸", "控油"),
                List.of("炸鸡"), 90, false);
        KeywordAssistantSkill vegetable = new KeywordAssistantSkill(
                new AssistantSkillPlan("vegetable-balance", "蔬菜", "VEGETABLE", "蔬菜", "补蔬菜"),
                List.of("蔬菜"), 80, false);
        KeywordAssistantSkill general = new KeywordAssistantSkill(
                new AssistantSkillPlan("balanced-diet", "均衡", "GENERAL", "均衡", "均衡策略"),
                List.of(), 0, true);
        AssistantSkillRegistry registry = new AssistantSkillRegistry(List.of(general, vegetable, fried));

        assertThat(registry.select("那晚餐呢？", List.of("中午吃了炸鸡")).skillName())
                .isEqualTo("fried-food-control");
        assertThat(registry.select("先不聊炸鸡，今天蔬菜够吗？", List.of("中午吃了炸鸡")).skillName())
                .isEqualTo("vegetable-balance");
    }

    @Test
    void ignoresNegatedKeywordAndUsesFallback() {
        KeywordAssistantSkill fatLoss = new KeywordAssistantSkill(
                new AssistantSkillPlan("fat-loss", "减脂", "FAT_LOSS", "减脂", "减脂策略"),
                List.of("减脂"), 10, false);
        KeywordAssistantSkill general = new KeywordAssistantSkill(
                new AssistantSkillPlan("balanced-diet", "均衡", "GENERAL", "均衡", "均衡策略"),
                List.of(), 0, true);
        AssistantSkillRegistry registry = new AssistantSkillRegistry(List.of(general, fatLoss));

        assertThat(registry.select("我现在不减脂，只想保持体重").skillName())
                .isEqualTo("balanced-diet");
    }
}
