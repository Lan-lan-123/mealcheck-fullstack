package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeResultPostProcessorTest {

    @Test
    void appliesAbsoluteAndRelativeThresholds() {
        AppProperties properties = properties(0.35, 0.70, 0.95);
        KnowledgeResultPostProcessor processor = new KnowledgeResultPostProcessor(properties);
        List<KnowledgeSnippet> candidates = List.of(
                snippet(1L, "优质蛋白", "鸡蛋和豆制品可以提供蛋白质", 0.90),
                snippet(2L, "蛋白质搭配", "鱼禽肉可以作为蛋白质来源", 0.64),
                snippet(3L, "低相关内容", "普通饮食建议", 0.62),
                snippet(4L, "低分内容", "无关内容", 0.34)
        );

        KnowledgeResultPostProcessor.Result result = processor.process(candidates, 5);

        assertThat(result.snippets()).extracting(KnowledgeSnippet::getId).containsExactly(1L, 2L);
        assertThat(result.thresholdFilteredCount()).isEqualTo(2);
        assertThat(result.effectiveThreshold()).isEqualTo(0.63);
    }

    @Test
    void removesNearDuplicateResultsAndRefillsFromLaterCandidates() {
        AppProperties properties = properties(0.0, 0.0, 0.70);
        KnowledgeResultPostProcessor processor = new KnowledgeResultPostProcessor(properties);
        List<KnowledgeSnippet> candidates = List.of(
                snippet(1L, "蔬菜建议", "每天增加绿叶蔬菜，补充膳食纤维和维生素。", 0.90),
                snippet(2L, "蔬菜建议", "每天增加绿叶蔬菜，补充膳食纤维和维生素。", 0.85),
                snippet(3L, "主食建议", "用燕麦和杂粮替代部分精制主食。", 0.80)
        );

        KnowledgeResultPostProcessor.Result result = processor.process(candidates, 2);

        assertThat(result.snippets()).extracting(KnowledgeSnippet::getId).containsExactly(1L, 3L);
        assertThat(result.duplicateFilteredCount()).isEqualTo(1);
    }

    @Test
    void canDisableFilteringForControlledBenchmarkComparison() {
        AppProperties properties = properties(0.90, 0.90, 0.10);
        properties.getKnowledge().setResultFilterEnabled(false);
        KnowledgeResultPostProcessor processor = new KnowledgeResultPostProcessor(properties);
        List<KnowledgeSnippet> candidates = List.of(
                snippet(1L, "相同", "相同内容", 0.20),
                snippet(2L, "相同", "相同内容", 0.10)
        );

        KnowledgeResultPostProcessor.Result result = processor.process(candidates, 2);

        assertThat(result.snippets()).hasSize(2);
        assertThat(result.thresholdFilteredCount()).isZero();
        assertThat(result.duplicateFilteredCount()).isZero();
    }

    @Test
    void rejectsEvidenceWhenRawRerankerScoreIsBelowConfiguredMinimum() {
        AppProperties properties = properties(0.0, 0.0, 0.90);
        properties.getKnowledge().setRerankerMinScore(0.20);
        KnowledgeResultPostProcessor processor = new KnowledgeResultPostProcessor(properties);
        KnowledgeSnippet candidate = snippet(1L, "无关知识", "与问题没有直接关系", 1.0);
        candidate.setRoughScore(0.80);
        candidate.setRoughRank(1);
        candidate.setRerankerRawScore(0.08);
        candidate.setRerankerRank(1);
        candidate.setReranked(true);

        KnowledgeResultPostProcessor.Result result = processor.process(List.of(candidate), 5);

        assertThat(result.snippets()).isEmpty();
        assertThat(result.thresholdFilteredCount()).isEqualTo(1);
        assertThat(result.topRoughScore()).isEqualTo(0.80);
        assertThat(result.topRerankerRawScore()).isEqualTo(0.08);
        assertThat(result.topFinalScore()).isEqualTo(1.0);
        assertThat(result.rerankerApplied()).isTrue();
    }

    private AppProperties properties(double minScore, double relativeScore, double dedupSimilarity) {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setResultMinScore(minScore);
        properties.getKnowledge().setResultRelativeScore(relativeScore);
        properties.getKnowledge().setResultDedupSimilarity(dedupSimilarity);
        return properties;
    }

    private KnowledgeSnippet snippet(Long id, String title, String content, double score) {
        return new KnowledgeSnippet(id, title, content, "general", score);
    }
}
