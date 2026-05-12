package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeIndexService {
    private static final String SOURCE = "diet_guides.md";
    private final PgVectorKnowledgeService vectorKnowledgeService;
    private final AppProperties properties;

    public long countChunks() {
        return vectorKnowledgeService.count();
    }

    public KnowledgeIndexService(PgVectorKnowledgeService vectorKnowledgeService, AppProperties properties) {
        this.vectorKnowledgeService = vectorKnowledgeService;
        this.properties = properties;
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
            return vectorKnowledgeService.replaceAll(SOURCE, splitMarkdown(text));
        } catch (Exception e) {
            throw new IllegalStateException("知识库索引失败，请确认 PostgreSQL 已安装 pgvector 扩展。", e);
        }
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        return vectorKnowledgeService.search(query, limit);
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
