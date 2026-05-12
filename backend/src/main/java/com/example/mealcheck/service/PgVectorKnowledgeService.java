package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

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
            jdbcTemplate.update("INSERT INTO knowledge_chunks(source, title, content, embedding) VALUES (?, ?, ?, CAST(? AS vector))",
                    source, chunk.title(), chunk.content(), embeddingService.toPgVector(embedding));
            inserted++;
        }
        return inserted;
    }

    @Transactional
    public void add(String source, String title, String content) {
        float[] embedding = embeddingService.embed(content);
        jdbcTemplate.update(
                "INSERT INTO knowledge_chunks(source, title, content, embedding) VALUES (?, ?, ?, CAST(? AS vector))",
                source,
                title,
                content,
                embeddingService.toPgVector(embedding)
        );
    }

    @Transactional
    public boolean update(Long id, String title, String content) {
        float[] embedding = embeddingService.embed(content);
        return jdbcTemplate.update(
                "UPDATE knowledge_chunks SET title = ?, content = ?, embedding = CAST(? AS vector) WHERE id = ?",
                title,
                content,
                embeddingService.toPgVector(embedding),
                id
        ) > 0;
    }

    @Transactional
    public boolean delete(Long id) {
        return jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id = ?", id) > 0;
    }

    @Transactional
    public List<KnowledgeSnippet> search(String query, int limit) {
        String vector = embeddingService.toPgVector(embeddingService.embed(query));
        List<KnowledgeSnippet> results = jdbcTemplate.query("""
                SELECT id, title, content, 1 - (embedding <=> CAST(? AS vector)) AS score
                FROM knowledge_chunks
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNum) -> new KnowledgeSnippet(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getDouble("score")
        ), vector, vector, limit);

        for (KnowledgeSnippet result : results) {
            jdbcTemplate.update(
                    "UPDATE knowledge_chunks SET hit_count = COALESCE(hit_count, 0) + 1, last_hit_at = CURRENT_TIMESTAMP WHERE id = ?",
                    result.getId()
            );
        }

        return results;
    }

    public record KnowledgeChunk(String title, String content) {}

    public java.util.List<com.example.mealcheck.dto.AdminKnowledgeChunkResponse> listAdminChunks(String titleFilter) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, source, title, content, COALESCE(hit_count, 0) AS hit_count, last_hit_at
            FROM knowledge_chunks
            """);
        java.util.List<Object> args = new java.util.ArrayList<>();

        if (titleFilter != null && !titleFilter.isBlank()) {
            sql.append(" WHERE LOWER(title) LIKE LOWER(?)");
            args.add("%" + titleFilter.trim() + "%");
        }

        sql.append(" ORDER BY id ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Long id = rs.getLong("id");
            String source = rs.getString("source");
            String title = rs.getString("title");
            String content = rs.getString("content");
            Timestamp lastHitAt = rs.getTimestamp("last_hit_at");

            String preview = content == null ? "" : content;
            if (preview.length() > 120) {
                preview = preview.substring(0, 120) + "...";
            }

            return new com.example.mealcheck.dto.AdminKnowledgeChunkResponse(
                    id,
                    source,
                    title,
                    content,
                    preview,
                    384,
                    content == null ? 0 : content.length(),
                    rs.getLong("hit_count"),
                    lastHitAt == null ? null : lastHitAt.toLocalDateTime()
            );
        }, args.toArray());
    }

}
