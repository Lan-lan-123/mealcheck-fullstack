package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownKnowledgeChunkerTest {

    @Test
    void enhancedFrameworkBindsHeadingsToBodiesWithoutExceedingBudget() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(30);
        properties.getKnowledge().setChunkMaxTokens(65);
        properties.getKnowledge().setChunkOverlapTokens(5);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String body = java.util.stream.IntStream.range(0, 12).mapToObj(i ->
                "**判断条件" + i + "**\n\n场景" + i + "应保留未知信息，不能从外观推算精确数值。")
                .collect(Collectors.joining("\n\n"));
        String markdown = "## 分节知识\n" + body;
        var baseline = chunker.split(markdown, MarkdownKnowledgeChunker.FRAMEWORK);
        assertThat(baseline).anySatisfy(chunk -> assertThat(chunk.content()).endsWith("**"));
        var enhanced = chunker.split(markdown, MarkdownKnowledgeChunker.ENHANCED_FRAMEWORK);
        assertThat(enhanced).allSatisfy(chunk -> {
            assertThat(chunk.content()).doesNotEndWith("**");
            assertThat(chunk.tokenCount()).isBetween(1, 65);
            assertThat(chunk.tokenCount()).isEqualTo(chunker.estimateTokens(chunk.content()));
        });
        for (int i = 0; i < 12; i++) {
            String headingAndBody = "**判断条件" + i + "**\n\n场景" + i + "应保留未知信息，不能从外观推算精确数值。";
            assertThat(enhanced).anySatisfy(chunk -> assertThat(chunk.content()).contains(headingAndBody));
        }
        assertThat(chunker.split(markdown, MarkdownKnowledgeChunker.ENHANCED_FRAMEWORK)).isEqualTo(enhanced);
    }

    @Test
    void enhancedFrameworkPreservesUnchangedPlainWindowsAndKeepsTerminalHeadingInItsTopic() {
        var chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String plain = "## 普通知识\n" + "保留实际份量信息，不推算未知的配方。\n\n".repeat(65);
        assertThat(chunker.split(plain, MarkdownKnowledgeChunker.ENHANCED_FRAMEWORK))
                .isEqualTo(chunker.split(plain, MarkdownKnowledgeChunker.FRAMEWORK));
        var chunks = chunker.split("## 主题甲\n正文。\n\n**仅标题**\n## 主题乙\n其他正文。",
                MarkdownKnowledgeChunker.ENHANCED_FRAMEWORK);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).title()).isEqualTo("主题甲");
        assertThat(chunks.get(0).content()).endsWith("**仅标题**");
        assertThat(chunks.get(1).content()).isEqualTo("其他正文。");
    }

    @Test
    void enhancedFrameworkSplitsBundledCorpusWithoutOrphanHeadings() throws Exception {
        var chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String markdown = new String(new ClassPathResource("knowledge/diet_guides.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var chunks = chunker.split(markdown, MarkdownKnowledgeChunker.ENHANCED_FRAMEWORK);
        assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isBetween(1, 500);
            assertThat(chunk.content()).doesNotEndWith("**");
        });
        assertThat(chunks.stream().map(PgVectorKnowledgeService.KnowledgeChunk::chunkKey)).doesNotHaveDuplicates();
        assertThat(chunks.stream().map(PgVectorKnowledgeService.KnowledgeChunk::title).distinct()).hasSize(200);
    }

    @Test
    void quotedSentenceEndIncludesTheClosingQuote() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(30);
        properties.getKnowledge().setChunkMaxTokens(55);
        properties.getKnowledge().setChunkOverlapTokens(5);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String body = ("“" + "甲".repeat(18) + "。”").repeat(12);
        var chunks = chunker.split("<!-- mealcheck:independent-topics -->\n## 引号\n" + body);
        assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(chunk -> {
            assertThat(chunk.content()).startsWith("“").endsWith("。”");
            assertThat(chunk.tokenCount()).isBetween(30, 55);
        });
    }

    @Test
    void aSentenceEndingExactlyAtTheMinimumIsNotSkippedForAnArbitraryTokenEnd() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(30);
        properties.getKnowledge().setChunkMaxTokens(55);
        properties.getKnowledge().setChunkOverlapTokens(5);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String first = "甲".repeat(29) + "。", second = "乙".repeat(54) + "。";
        var chunks = chunker.split("<!-- mealcheck:independent-topics -->\n## 最小边界\n" + first + second);
        assertThat(chunks).extracting(PgVectorKnowledgeService.KnowledgeChunk::content).containsExactly(first, second);
    }

    @Test
    void recursiveOverlapKeepsCompleteSentencesInsteadOfAClippedTokenSuffix() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(40);
        properties.getKnowledge().setChunkMaxTokens(70);
        properties.getKnowledge().setChunkOverlapTokens(5);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String body = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> "第" + i + "项需要先确认实际条件，再给出可执行建议。")
                .collect(Collectors.joining());
        var chunks = chunker.split("<!-- mealcheck:independent-topics -->\n## 判断条件\n" + body);
        assertThat(chunks).hasSizeGreaterThan(2);
        for (var chunk : chunks) {
            int start = body.indexOf(chunk.content());
            assertThat(start).isNotNegative();
            assertThat(start == 0 || body.charAt(start - 1) == '。').isTrue();
            assertThat(chunk.content()).endsWith("。");
            assertThat(chunk.tokenCount()).isBetween(40, 70);
        }
    }

    @Test
    void recursiveHeadingStaysWithItsBodyAndNeverEndsAChunkAlone() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(30);
        properties.getKnowledge().setChunkMaxTokens(65);
        properties.getKnowledge().setChunkOverlapTokens(5);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String body = java.util.stream.IntStream.range(0, 12).mapToObj(i ->
                "**判断条件" + i + "**\n\n场景" + i + "应保留未知信息，不能从外观推算精确数值。")
                .collect(Collectors.joining("\n\n"));
        var chunks = chunker.split("<!-- mealcheck:independent-topics -->\n## 分节知识\n" + body);
        assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(chunk -> {
            assertThat(chunk.content()).doesNotEndWith("**");
            assertThat(chunk.content()).doesNotStartWith("*\n");
            assertThat(chunk.tokenCount()).isBetween(30, 65);
        });
        for (int i = 0; i < 12; i++) {
            String headingAndBody = "**判断条件" + i + "**\n\n场景" + i + "应保留未知信息，不能从外观推算精确数值。";
            assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.content()).contains(headingAndBody));
        }
    }

    @Test
    void minimumTailUsesEarlierCompleteContextWithoutCuttingThePreviousSentenceEnd() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMinTokens(30);
        properties.getKnowledge().setChunkMaxTokens(55);
        properties.getKnowledge().setChunkOverlapTokens(5);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String body = "甲".repeat(35) + "。" + "乙".repeat(40) + "。" + "丙".repeat(16) + "。"
                + "丁".repeat(33) + "。" + "戊".repeat(11) + "。";
        var chunks = chunker.split("<!-- mealcheck:independent-topics -->\n## 尾部条件\n" + body);
        assertThat(chunks).hasSize(4).allSatisfy(chunk -> {
            int start = body.indexOf(chunk.content());
            assertThat(start == 0 || body.charAt(start - 1) == '。').isTrue();
            assertThat(chunk.content()).endsWith("。");
            assertThat(chunk.tokenCount()).isBetween(30, 55);
        });
        assertThat(chunks.get(3).content()).startsWith("丁".repeat(33) + "。");
    }

    @Test
    void allStrategiesKeepTopicsSeparateAndBoundLongChineseAndEnglishText() {
        var properties = new AppProperties();
        var chunker = new MarkdownKnowledgeChunker(properties);
        String markdown = "<!-- mealcheck:independent-topics -->\n## 中文主题\n" + "饮食记录需要保留判断条件。".repeat(120)
                + "\n## English\n" + "Keep the recommendation and its conditions together. ".repeat(140);
        for (String strategy : MarkdownKnowledgeChunker.SELECTABLE_STRATEGIES) {
            var chunks = chunker.split(markdown, strategy);
            assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(chunk -> {
                assertThat(chunk.tokenCount()).isBetween(1, 500);
                assertThat(chunk.title()).isIn("中文主题", "English");
            });
            assertThat(chunks.stream().filter(chunk -> chunk.title().equals("中文主题")))
                    .noneSatisfy(chunk -> assertThat(chunk.content()).contains("recommendation"));
            assertThat(chunks.stream().filter(chunk -> chunk.title().equals("English")))
                    .noneSatisfy(chunk -> assertThat(chunk.content()).contains("饮食"));
            assertThat(chunker.split(markdown, strategy)).isEqualTo(chunks);
        }
        assertThat(properties.getKnowledge().getSplitterStrategy()).isEqualTo("recursive-markdown");
    }

    @Test
    void structurePreservesSubsectionsAndCompleteListBlocksWithoutMinimumPadding() {
        var chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String list = "- 记录实际食用份量。\n- 未知重量保留未知。\n- 不从照片推算精确营养。";
        String markdown = "## 饮食记录\n开头说明。\n\n**操作建议**\n" + list + "\n\n**判断限制**\n照片无法确认全部配方。";
        var current = chunker.split(markdown, MarkdownKnowledgeChunker.RECURSIVE);
        var structured = chunker.split(markdown, MarkdownKnowledgeChunker.STRUCTURAL);
        assertThat(current).hasSize(1);
        assertThat(structured).extracting(PgVectorKnowledgeService.KnowledgeChunk::title)
                .containsExactly("饮食记录", "饮食记录 / 操作建议", "饮食记录 / 判断限制");
        assertThat(structured.get(1).content()).isEqualTo(list);
        assertThat(structured.get(2).content()).isEqualTo("照片无法确认全部配方。");
    }

    @Test
    void structuralPackingDoesNotBreakFittingParagraphsAndKeepsOverlapInsideSection() {
        var properties = new AppProperties();
        properties.getKnowledge().setChunkMaxTokens(100);
        properties.getKnowledge().setChunkMinTokens(50);
        properties.getKnowledge().setChunkOverlapTokens(10);
        var chunker = new MarkdownKnowledgeChunker(properties);
        String first = "甲".repeat(70), second = "乙".repeat(70);
        var chunks = chunker.split("## 主题\n" + first + "\n\n" + second, MarkdownKnowledgeChunker.STRUCTURAL);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).isEqualTo(first);
        assertThat(chunks.get(1).content()).isEqualTo("甲".repeat(10) + "\n\n" + second);
    }

    @Test
    void frameworkAndStructureCanSplitTheBundledCorpusWithinTheSharedBudget() throws Exception {
        var chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String markdown = new String(new ClassPathResource("knowledge/diet_guides.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String strategy : List.of(MarkdownKnowledgeChunker.FRAMEWORK, MarkdownKnowledgeChunker.STRUCTURAL)) {
            var chunks = chunker.split(markdown, strategy);
            assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
                assertThat(chunk.content()).isNotBlank();
                assertThat(chunk.tokenCount()).isBetween(1, 500);
            });
            assertThat(chunks.stream().map(PgVectorKnowledgeService.KnowledgeChunk::chunkKey)).doesNotHaveDuplicates();
            System.out.println("Bundled corpus splitter=" + strategy + " chunks=" + chunks.size());
        }
    }

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
    void bundledKnowledgeContainsTwoHundredDistinctIndependentTopics() throws Exception {
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(new AppProperties());
        String markdown = new String(new ClassPathResource("knowledge/diet_guides.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(markdown);

        var metadata = new ObjectMapper().readTree(new ClassPathResource("knowledge/diet_guides_metadata.json")
                .getInputStream());
        assertThat(chunks).hasSize(metadata.path("chunkCount").asInt()).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isBetween(300, 500));
        assertThat(metadata.path("topicCount").asInt()).isEqualTo(200);
        assertThat(metadata.path("splitterRevision").asText()).isEqualTo(MarkdownKnowledgeChunker.RECURSIVE_REVISION);
        assertThat(metadata.path("independentlyReviewed").asBoolean()).isFalse();
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(markdown.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(metadata.path("corpusSha256").asText());
        var byTitle = chunks.stream().collect(Collectors.groupingBy(PgVectorKnowledgeService.KnowledgeChunk::title));
        assertThat(byTitle).hasSize(200);
        for (var topic : metadata.path("topics")) {
            var parts = byTitle.get(topic.path("title").asText());
            assertThat(parts).as(topic.path("title").asText()).isNotNull()
                    .hasSize(topic.path("actualChunks").asInt());
            int[] expectedBounds = switch (topic.path("lengthBucket").asText()) {
                case "SHORT" -> new int[]{2, 3};
                case "MEDIUM" -> new int[]{4, 6};
                case "LONG" -> new int[]{7, 10};
                case "EXTENDED" -> new int[]{12, 20};
                default -> throw new AssertionError("Unknown length bucket");
            };
            assertThat(topic.path("minChunks").asInt()).isEqualTo(expectedBounds[0]);
            assertThat(topic.path("maxChunks").asInt()).isEqualTo(expectedBounds[1]);
            assertThat(parts.size()).as(topic.path("title").asText())
                    .isBetween(expectedBounds[0], expectedBounds[1]);
            assertThat(topic.path("lengthPlanSatisfied").asBoolean()).isTrue();
            assertThat(parts.stream().anyMatch(part -> part.content().contains("https://"))).isTrue();
        }
        assertThat(metadata.path("lengthPolicy").asText()).isEqualTo("CONTENT_QUALITY_AND_ORIGINAL_LENGTH_BOTH_REQUIRED");
        assertThat(metadata.path("lengthGoalDeviationCount").asInt()).isZero();
        assertThat(metadata.path("lengthPlanStatus").asText()).isEqualTo("SATISFIED");
        var buckets = new java.util.ArrayList<String>();
        metadata.path("topics").forEach(topic -> buckets.add(topic.path("lengthBucket").asText()));
        assertThat(buckets.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(Map.of("SHORT", 40L, "MEDIUM", 60L, "LONG", 60L, "EXTENDED", 40L));
        assertThat(chunks.stream().map(PgVectorKnowledgeService.KnowledgeChunk::contentHash)).doesNotHaveDuplicates();
        assertThat(chunks.stream().map(PgVectorKnowledgeService.KnowledgeChunk::chunkKey)).doesNotHaveDuplicates();
        assertThat(chunks.stream().map(PgVectorKnowledgeService.KnowledgeChunk::chunkIndex))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void independentTopicsDoNotMergeOrCarryUnrelatedContext() {
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(new AppProperties());
        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(
                "<!-- mealcheck:independent-topics -->\n# Guide\n## Topic A\n甲"
                        + "\n## Topic B\n乙");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).title()).isEqualTo("Topic A");
        assertThat(chunks.get(0).content()).isEqualTo("甲");
        assertThat(chunks.get(1).title()).isEqualTo("Topic B");
        assertThat(chunks.get(1).content()).isEqualTo("乙");
    }

    @Test
    void independentLongTopicStillSplitsWithInternalOverlap() {
        MarkdownKnowledgeChunker chunker = new MarkdownKnowledgeChunker(new AppProperties());
        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(
                "<!-- mealcheck:independent-topics -->\n## Long topic\n" + "甲".repeat(900));

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.title()).isEqualTo("Long topic");
            assertThat(chunk.tokenCount()).isBetween(300, 500);
        });
        assertThat(chunks.get(1).content()).startsWith(chunks.get(0).content().substring(
                chunks.get(0).content().length() - 50));
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
