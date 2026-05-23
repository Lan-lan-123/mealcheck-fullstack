package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class KnowledgeIndexService {
    private static final String SOURCE = "diet_guides.md";
    private static final String SEARCH_CACHE_PREFIX = "rag:search:";
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(10);
    private final PgVectorKnowledgeService vectorKnowledgeService;
    private final AppProperties properties;
    private final RedisCacheService redisCacheService;
    private final RagEvaluationService ragEvaluationService;

    public long countChunks() {
        return vectorKnowledgeService.count();
    }

    public KnowledgeIndexService(PgVectorKnowledgeService vectorKnowledgeService,
                                 AppProperties properties,
                                 RedisCacheService redisCacheService,
                                 RagEvaluationService ragEvaluationService) {
        this.vectorKnowledgeService = vectorKnowledgeService;
        this.properties = properties;
        this.redisCacheService = redisCacheService;
        this.ragEvaluationService = ragEvaluationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void autoIndex() {
        if (properties.getKnowledge().isAutoIndex() && vectorKnowledgeService.count() == 0) {
            reindex();
        }
    }

    public int reindex() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/diet_guides.md");
            String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int count = vectorKnowledgeService.replaceAll(SOURCE, splitMarkdown(text));
            clearSearchCache();
            return count;
        } catch (Exception e) {
            throw new IllegalStateException("知识库索引失败，请确认 PostgreSQL 已安装 pgvector 扩展。", e);
        }
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        String key = searchCacheKey(query, limit);
        List<KnowledgeSnippet> snippets = redisCacheService.getJson(key, new TypeReference<List<KnowledgeSnippet>>() {})
                .orElseGet(() -> {
                    List<KnowledgeSnippet> freshSnippets = vectorKnowledgeService.search(query, limit);
                    redisCacheService.setJson(key, freshSnippets, SEARCH_CACHE_TTL);
                    return freshSnippets;
                });
        ragEvaluationService.recordSearch(query, snippets);
        return snippets;
    }

    public void recordHits(List<Long> ids) {
        vectorKnowledgeService.recordHits(ids);
    }

    public void clearSearchCache() {
        redisCacheService.deleteByPrefix(SEARCH_CACHE_PREFIX);
    }

    private String searchCacheKey(String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return SEARCH_CACHE_PREFIX + limit + ":" + Integer.toHexString(normalized.hashCode());
    }

    private List<PgVectorKnowledgeService.KnowledgeChunk> splitMarkdown(String text) {
        List<PgVectorKnowledgeService.KnowledgeChunk> chunks = new ArrayList<>();
        String[] sections = text.split("\\n## ");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isBlank() || trimmed.startsWith("# MealCheck")) continue;
            String[] lines = trimmed.split("\\n", 2);
            String title = lines[0].replace("##", "").trim();
            String content = lines.length > 1 ? lines[1].trim() : trimmed;
            chunks.add(new PgVectorKnowledgeService.KnowledgeChunk(title, content));
        }
        return chunks;
    }
}
