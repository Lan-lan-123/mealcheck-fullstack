package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCategoryClassifierTest {
    @Test void topicCategorySurvivesIncidentalCalorieAndProteinExamples() {
        assertThat(KnowledgeCategoryClassifier.infer("主食结构建议", "重量未知，不编造热量；还可以观察蛋白质配菜。")).isEqualTo("staple");
        assertThat(KnowledgeCategoryClassifier.infer("蔬菜结构建议", "不能从照片推算热量，也不能保证整餐蛋白质充足。")).isEqualTo("vegetable");
        assertThat(KnowledgeCategoryClassifier.infer("高油烹饪风险", "不从照片估算热量或用油克数。")).isEqualTo("high_oil");
    }

    @Test void neutralManualTitlesRetainContentFallbackAndQueriesKeepExistingPriority() {
        assertThat(KnowledgeCategoryClassifier.infer("自定义知识", "可以选择明确的蛋白质食物。")).isEqualTo("muscle_gain");
        assertThat(KnowledgeCategoryClassifier.infer("减脂时怎么选主食", "")).isEqualTo("fat_loss");
        assertThat(KnowledgeCategoryClassifier.infer(null, null)).isEqualTo("general");
    }
}
