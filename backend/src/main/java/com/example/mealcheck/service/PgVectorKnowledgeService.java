package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.AdminAssistantStatsResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkPageResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

@Service
public class PgVectorKnowledgeService implements InitializingBean {
    private final JdbcTemplate jdbcTemplate;
    private final HashEmbeddingService embeddingService;

    public PgVectorKnowledgeService(JdbcTemplate jdbcTemplate, HashEmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id BIGSERIAL PRIMARY KEY,
                    source VARCHAR(255) NOT NULL,
                    title VARCHAR(255),
                    content TEXT NOT NULL,
                    embedding vector(384) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding ON knowledge_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS hit_count BIGINT DEFAULT 0");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS last_hit_at TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS category VARCHAR(64) DEFAULT 'general'");
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_chunks", Long.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public int replaceAll(String source, List<KnowledgeChunk> chunks) {
        jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE source = ?", source);
        int inserted = 0;
        for (KnowledgeChunk chunk : chunks) {
            float[] embedding = embeddingService.embed(chunk.content());
            jdbcTemplate.update("INSERT INTO knowledge_chunks(source, title, content, category, embedding) VALUES (?, ?, ?, ?, CAST(? AS vector))",
                    source, chunk.title(), chunk.content(), inferCategory(chunk.title(), chunk.content()), embeddingService.toPgVector(embedding));
            inserted++;
        }
        return inserted;
    }

    @Transactional
    public void add(String source, String title, String content) {
        float[] embedding = embeddingService.embed(content);
        jdbcTemplate.update(
                "INSERT INTO knowledge_chunks(source, title, content, category, embedding) VALUES (?, ?, ?, ?, CAST(? AS vector))",
                source,
                title,
                content,
                inferCategory(title, content),
                embeddingService.toPgVector(embedding)
        );
    }

    @Transactional
    public boolean update(Long id, String title, String content) {
        float[] embedding = embeddingService.embed(content);
        return jdbcTemplate.update(
                "UPDATE knowledge_chunks SET title = ?, content = ?, category = ?, embedding = CAST(? AS vector) WHERE id = ?",
                title,
                content,
                inferCategory(title, content),
                embeddingService.toPgVector(embedding),
                id
        ) > 0;
    }

    @Transactional
    public boolean delete(Long id) {
        return jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id = ?", id) > 0;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSnippet> search(String query, int limit) {
        String vector = embeddingService.toPgVector(embeddingService.embed(query));
        String category = inferCategory(query, query);
        return jdbcTemplate.query("""
                SELECT id, title, content, COALESCE(category, 'general') AS category, 1 - (embedding <=> CAST(? AS vector)) AS score
                FROM knowledge_chunks
                ORDER BY CASE WHEN COALESCE(category, 'general') = ? THEN 0 ELSE 1 END,
                         embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNum) -> new KnowledgeSnippet(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("category"),
                rs.getDouble("score")
        ), vector, category, vector, limit);
    }

    @Transactional
    public void recordHits(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        ids.stream()
                .filter(id -> id != null)
                .distinct()
                .forEach(id -> jdbcTemplate.update("""
                        UPDATE knowledge_chunks
                        SET hit_count = COALESCE(hit_count, 0) + 1,
                            last_hit_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, id));
    }

    public record KnowledgeChunk(String title, String content) {}

    public AdminKnowledgeChunkPageResponse listAdminChunks(String keyword,
                                                           String source,
                                                           String sort,
                                                           int page,
                                                           int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        FilterSql filterSql = buildFilterSql(keyword, source);

        StringBuilder sql = new StringBuilder("""
            SELECT id, source, title, content, COALESCE(category, 'general') AS category, COALESCE(hit_count, 0) AS hit_count, last_hit_at
            FROM knowledge_chunks
            """);
        sql.append(filterSql.whereClause());

        if ("hits".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY COALESCE(hit_count, 0) DESC, id ASC");
        } else if ("recentHit".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY last_hit_at DESC NULLS LAST, id ASC");
        } else {
            sql.append(" ORDER BY id ASC");
        }
        sql.append(" LIMIT ? OFFSET ?");

        java.util.List<Object> pageArgs = new java.util.ArrayList<>(filterSql.args());
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunks" + filterSql.whereClause(),
                Long.class,
                filterSql.args().toArray()
        );

        java.util.List<AdminKnowledgeChunkResponse> pageContent = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Long id = rs.getLong("id");
            String chunkSource = rs.getString("source");
            String title = rs.getString("title");
            String content = rs.getString("content");
            String category = rs.getString("category");
            Timestamp lastHitAt = rs.getTimestamp("last_hit_at");

            String preview = content == null ? "" : content;
            if (preview.length() > 120) {
                preview = preview.substring(0, 120) + "...";
            }

            return new AdminKnowledgeChunkResponse(
                    id,
                    chunkSource,
                    title,
                    content,
                    preview,
                    category,
                    384,
                    content == null ? 0 : content.length(),
                    rs.getLong("hit_count"),
                    lastHitAt == null ? null : lastHitAt.toLocalDateTime()
            );
        }, pageArgs.toArray());

        long safeTotal = total == null ? 0 : total;
        int totalPages = safeTotal == 0 ? 0 : (int) Math.ceil((double) safeTotal / safeSize);
        return new AdminKnowledgeChunkPageResponse(pageContent, safeTotal, totalPages, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public List<AdminAssistantStatsResponse.MetricItem> topHitChunks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.query("""
                SELECT COALESCE(NULLIF(title, ''), CONCAT('知识片段 ', id)) AS label,
                       COALESCE(hit_count, 0) AS value
                FROM knowledge_chunks
                WHERE COALESCE(hit_count, 0) > 0
                ORDER BY COALESCE(hit_count, 0) DESC, last_hit_at DESC NULLS LAST, id ASC
                LIMIT ?
                """, (rs, rowNum) -> new AdminAssistantStatsResponse.MetricItem(
                rs.getString("label"),
                rs.getInt("value")
        ), safeLimit);
    }

    private FilterSql buildFilterSql(String keyword, String source) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        java.util.List<String> predicates = new java.util.ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            predicates.add("(LOWER(title) LIKE LOWER(?) OR LOWER(content) LIKE LOWER(?))");
            String value = "%" + keyword.trim() + "%";
            args.add(value);
            args.add(value);
        }

        if (source != null && !source.isBlank() && !"all".equalsIgnoreCase(source)) {
            predicates.add("source = ?");
            args.add(source.trim());
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        return new FilterSql(whereClause, args);
    }

    private record FilterSql(String whereClause, java.util.List<Object> args) {}

    private String inferCategory(String title, String content) {
        String text = ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "减脂", "减肥", "控卡", "热量", "fat")) return "fat_loss";
        if (containsAny(text, "增肌", "蛋白", "肌肉", "训练", "muscle")) return "muscle_gain";
        if (containsAny(text, "油炸", "高油", "炸鸡", "烧烤", "烤肉", "红烧")) return "high_oil";
        if (containsAny(text, "甜", "含糖", "奶茶", "饮料", "糖")) return "sugar";
        if (containsAny(text, "蔬菜", "纤维", "绿叶菜", "维生素")) return "vegetable";
        if (containsAny(text, "主食", "米饭", "面", "碳水", "红薯")) return "staple";
        return "general";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

}
