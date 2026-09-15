package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownKnowledgeChunkerTest {

    @Test
    void createsBalancedTokenWindowsWithConfiguredOverlap() {
        AppProperties properties = new AppProperties();
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(properties);
        String body = "饮食结构建议".repeat(200);

        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split("## 长篇知识\n" + body);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isBetween(300, 500));
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).content();
            String current = chunks.get(i).content();
            assertThat(previous.substring(previous.length() - 50))
                    .isEqualTo(current.substring(0, 50));
        }
    }

    @Test
    void keepsStableKeysAndHashesForUnchangedKnowledge() {
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String markdown = "## 蛋白质建议\n鸡蛋、鱼虾和豆制品都是优质蛋白来源。";

        List<PgVectorKnowledgeService.KnowledgeChunk> first = chunker.split(markdown);
        List<PgVectorKnowledgeService.KnowledgeChunk> second = chunker.split(markdown);

        assertThat(first).hasSize(1);
        assertThat(second.get(0).chunkKey()).isEqualTo(first.get(0).chunkKey());
        assertThat(second.get(0).contentHash()).isEqualTo(first.get(0).contentHash());
        assertThat(first.get(0).title()).isEqualTo("蛋白质建议");
    }

    @Test
    void bundledKnowledgeUsesTargetTokenRange() throws Exception {
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String markdown = new String(new ClassPathResource("knowledge/diet_guides.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(markdown);

        assertThat(chunks).hasSize(19).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isBetween(300, 500));
    }

    @Test
    void mergedShortSectionsKeepFiftyTokenOverlap() {
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String markdown = "## 甲\n" + "甲".repeat(100)
                + "\n## 乙\n" + "乙".repeat(100)
                + "\n## 丙\n" + "丙".repeat(100)
                + "\n## 丁\n" + "丁".repeat(100)
                + "\n## 戊\n" + "戊".repeat(100)
                + "\n## 己\n" + "己".repeat(100);

        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(markdown);

        assertThat(chunks).hasSize(2);
        String overlap = chunks.get(0).content().substring(chunks.get(0).content().length() - 50);
        assertThat(chunks.get(1).content()).startsWith(overlap);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isBetween(300, 500));
    }

    @Test
    void recursiveStrategyPrefersParagraphBoundaryBeforeTokenFallback() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(15);
        properties.getKnowledge().setChunkMaxTokens(30);
        properties.getKnowledge().setChunkOverlapTokens(5);
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(properties);
        String first = "甲".repeat(12) + "。";
        String second = "乙".repeat(12) + "。";
        String third = "丙".repeat(12) + "。";

        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(
                "## 递归切分\n" + first + "\n\n" + second + "\n\n" + third);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).endsWith(second);
        assertThat(chunks.get(1).content()).contains(third);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isBetween(15, 30));
    }

    @Test
    void legacyWindowStrategyRemainsAvailableForComparison() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setSplitterStrategy("legacy-window");
        properties.getKnowledge().setChunkMinTokens(15);
        properties.getKnowledge().setChunkMaxTokens(30);
        properties.getKnowledge().setChunkOverlapTokens(5);
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(properties);
        String markdown = "## 对照切分\n"
                + "甲".repeat(12) + "。\n\n"
                + "乙".repeat(12) + "。\n\n"
                + "丙".repeat(12) + "。";

        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).doesNotEndWith("。");
    }
}
