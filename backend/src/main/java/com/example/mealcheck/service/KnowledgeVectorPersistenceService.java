package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeVectorPersistenceService {
    private final JdbcTemplate jdbcTemplate;
    private final VectorCompressionService compressionService;

    public KnowledgeVectorPersistenceService(JdbcTemplate jdbcTemplate,
                                             VectorCompressionService compressionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.compressionService = compressionService;
    }

    @Transactional
    public void add(String source, KnowledgeVectorRow row) {
        insert(source, row);
    }

    @Transactional
    public boolean update(Long id, KnowledgeVectorRow row) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_chunks
                SET title = ?, content = ?, category = ?, embedding = %s, embedding_signature = ?,
                    content_hash = ?, token_count = ?, source_version = ?, source_updated_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """.formatted(compressionService.castParameter()),
                row.title(), row.content(), row.category(), row.vector(), row.signature(),
                row.contentHash(), row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt(), id
        ) > 0;
    }

    @Transactional
    public void syncSource(String source, List<KnowledgeSyncRow> rows, List<Long> deletedIds) {
        for (KnowledgeSyncRow row : rows) {
            if (row.id() == null) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_chunks(
                            source, chunk_key, chunk_index, title, content, category, embedding,
                            embedding_signature, content_hash, token_count, source_version, source_updated_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """.formatted(compressionService.castParameter()),
                        source, row.chunkKey(), row.chunkIndex(), row.title(), row.content(), row.category(),
                        row.vector(), row.signature(), row.contentHash(), row.tokenCount(), row.sourceVersion(),
                        row.sourceUpdatedAt());
            } else if (row.vector() == null) {
                jdbcTemplate.update("""
                        UPDATE knowledge_chunks
                        SET chunk_index = ?, title = ?, content = ?, category = ?, content_hash = ?, token_count = ?,
                            source_updated_at = CASE WHEN source_version IS DISTINCT FROM ? THEN ? ELSE source_updated_at END,
                            updated_at = CASE WHEN source_version IS DISTINCT FROM ? THEN CURRENT_TIMESTAMP ELSE updated_at END,
                            source_version = ?
                        WHERE id = ?
                        """,
                        row.chunkIndex(), row.title(), row.content(), row.category(), row.contentHash(),
                        row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt(), row.sourceVersion(),
                        row.sourceVersion(), row.id());
            } else {
                jdbcTemplate.update("""
                        UPDATE knowledge_chunks
                        SET chunk_index = ?, title = ?, content = ?, category = ?, embedding = %s,
                            embedding_signature = ?, content_hash = ?, token_count = ?, source_version = ?,
                            source_updated_at = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.formatted(compressionService.castParameter()),
                        row.chunkIndex(), row.title(), row.content(), row.category(), row.vector(), row.signature(),
                        row.contentHash(), row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt(), row.id());
            }
        }
        for (Long id : deletedIds) {
            jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id = ?", id);
        }
    }

    private void insert(String source, KnowledgeVectorRow row) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_chunks(
                    source, chunk_key, title, content, category, embedding, embedding_signature,
                    content_hash, token_count, source_version, source_updated_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """.formatted(compressionService.castParameter()),
                source, row.chunkKey(), row.title(), row.content(), row.category(), row.vector(), row.signature(),
                row.contentHash(), row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt()
        );
    }

    public record KnowledgeVectorRow(String chunkKey,
                                     String title,
                                     String content,
                                     String category,
                                     String vector,
                                     String signature,
                                     String contentHash,
                                     int tokenCount,
                                     String sourceVersion,
                                     LocalDateTime sourceUpdatedAt) {}
    public record KnowledgeSyncRow(Long id,
                                   String chunkKey,
                                   int chunkIndex,
                                   String title,
                                   String content,
                                   String category,
                                   String vector,
                                   String signature,
                                   String contentHash,
                                   int tokenCount,
                                   String sourceVersion,
                                   LocalDateTime sourceUpdatedAt) {}
    public record KnowledgeVectorUpdate(Long id, String vector) {}
}
