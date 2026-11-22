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
    public void add(String source, KnowledgeVectorRow row, String actor) {
        context(actor,"管理员新增");
        insert(source,row);
    }

    @Transactional
    public boolean update(Long id, KnowledgeVectorRow row) {
        return update(id, row, null, null, null);
    }

    @Transactional
    public boolean update(Long id, KnowledgeVectorRow row, Long expectedRevision, String actor, String reason) {
        checkActiveSignature(row.signature());
        context(actor, reason);
        int changed = jdbcTemplate.update(
                """
                UPDATE knowledge_chunks
                SET title = ?, content = ?, category = ?, embedding = %s, embedding_signature = ?,
                    content_hash = ?, token_count = ?, source_version = ?, source_updated_at = ?,
                    embedding_input_tokens = ?, embedding_total_tokens = ?, embedding_updated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND (?::bigint IS NULL OR content_revision = ?)
                """.formatted(compressionService.castParameter()),
                row.title(), row.content(), row.category(), row.vector(), row.signature(),
                row.contentHash(), row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt(), row.inputTokens(), row.totalTokens(), id,
                expectedRevision, expectedRevision
        );
        if (changed == 0 && expectedRevision != null) assertRevision(id, expectedRevision);
        return changed > 0;
    }

    public void assertRevision(long id, long expectedRevision) {
        var rows = jdbcTemplate.queryForList("SELECT content_revision FROM knowledge_chunks WHERE id=?", Long.class, id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"知识已删除，请从内容历史查看或恢复。");
        if (rows.get(0) != expectedRevision) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"知识已被其他操作修改。草稿已保留，请查看最新正文并比较后再保存。");
    }

    private void context(String actor, String reason) {
        jdbcTemplate.queryForObject("SELECT set_config('mealcheck.knowledge_actor',?,true)", String.class, actor == null ? "system" : actor);
        jdbcTemplate.queryForObject("SELECT set_config('mealcheck.knowledge_reason',?,true)", String.class, reason == null ? "" : reason);
    }

    @Transactional
    public void delete(long id, long expectedRevision, String actor) {
        context(actor, "管理员删除");
        if (jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id=? AND content_revision=?",id,expectedRevision)==0)
            assertRevision(id,expectedRevision);
    }

    @Transactional
    public void restore(long id, long expectedRevision, java.util.Map<String,Object> snapshot, KnowledgeVectorRow row, String actor, String reason) {
        // Serialize restores of a deleted ID as well as edits of existing rows.
        jdbcTemplate.execute("LOCK TABLE knowledge_chunks IN SHARE ROW EXCLUSIVE MODE");
        checkActiveSignature(row.signature());
        long latest = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(revision),0) FROM knowledge_content_versions WHERE chunk_id=?",Long.class,id);
        if (latest != expectedRevision) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"知识版本已变化，请刷新内容历史后重试恢复。");
        context(actor, reason);
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_chunks WHERE id=?)",Boolean.class,id))) {
            update(id,row,expectedRevision,actor,reason);
            return;
        }
        String sql = """
            INSERT INTO knowledge_chunks(id,source,chunk_key,title,content,category,embedding,embedding_signature,
                content_hash,token_count,chunk_index,source_version,source_updated_at,embedding_input_tokens,embedding_total_tokens,embedding_updated_at,updated_at)
            VALUES (?,?,?,?,?,?,%s,?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """.formatted(compressionService.castParameter());
        try {
            jdbcTemplate.update(sql,id,snapshot.get("source"),snapshot.get("chunk_key"),row.title(),row.content(),row.category(),row.vector(),
                row.signature(),row.contentHash(),row.tokenCount(),snapshot.get("chunk_index"),row.sourceVersion(),row.sourceUpdatedAt(),row.inputTokens(),row.totalTokens());
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"同一来源的切片已被新知识占用，请比较新条目后手动合并。",conflict);
        }
    }

    @Transactional
    public void syncSource(String source, List<KnowledgeSyncRow> rows, List<Long> deletedIds) {
        if (!rows.isEmpty()) checkActiveSignature(rows.get(0).signature());
        for (KnowledgeSyncRow row : rows) {
            if (row.id() == null) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_chunks(
                            source, chunk_key, chunk_index, title, content, category, embedding,
                            embedding_signature, content_hash, token_count, source_version, source_updated_at, embedding_input_tokens, embedding_total_tokens, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """.formatted(compressionService.castParameter()),
                        source, row.chunkKey(), row.chunkIndex(), row.title(), row.content(), row.category(),
                        row.vector(), row.signature(), row.contentHash(), row.tokenCount(), row.sourceVersion(),
                        row.sourceUpdatedAt(), row.inputTokens(), row.totalTokens());
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
                            source_updated_at = ?, embedding_input_tokens = ?, embedding_total_tokens = ?,
                            embedding_updated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.formatted(compressionService.castParameter()),
                        row.chunkIndex(), row.title(), row.content(), row.category(), row.vector(), row.signature(),
                        row.contentHash(), row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt(), row.inputTokens(), row.totalTokens(), row.id());
            }
        }
        for (Long id : deletedIds) {
            jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id = ?", id);
        }
    }

    private void insert(String source, KnowledgeVectorRow row) {
        checkActiveSignature(row.signature());
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_chunks(
                    source, chunk_key, title, content, category, embedding, embedding_signature,
                    content_hash, token_count, source_version, source_updated_at, embedding_input_tokens, embedding_total_tokens, updated_at
                ) VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """.formatted(compressionService.castParameter()),
                source, row.chunkKey(), row.title(), row.content(), row.category(), row.vector(), row.signature(),
                row.contentHash(), row.tokenCount(), row.sourceVersion(), row.sourceUpdatedAt(), row.inputTokens(), row.totalTokens()
        );
    }

    private void checkActiveSignature(String signature) {
        // Acquire locks in publication order. A late writer must not reintroduce
        // vectors from the provider used before an administrator switched modes.
        jdbcTemplate.execute("LOCK TABLE knowledge_chunks IN ROW EXCLUSIVE MODE");
        String active = jdbcTemplate.queryForObject(
                "SELECT signature FROM knowledge_embedding_settings WHERE id=1 FOR SHARE", String.class);
        if (active != null && !active.equals(signature)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "向量方式已切换，请重试本次知识修改。");
        }
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
                                     LocalDateTime sourceUpdatedAt, Long inputTokens, Long totalTokens) {
        public KnowledgeVectorRow(String chunkKey,String title,String content,String category,String vector,String signature,
                                  String contentHash,int tokenCount,String sourceVersion,LocalDateTime sourceUpdatedAt) {
            this(chunkKey,title,content,category,vector,signature,contentHash,tokenCount,sourceVersion,sourceUpdatedAt,null,null);
        }
    }
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
                                   LocalDateTime sourceUpdatedAt, Long inputTokens, Long totalTokens) {
        public KnowledgeSyncRow(Long id,String chunkKey,int chunkIndex,String title,String content,String category,String vector,
                                String signature,String contentHash,int tokenCount,String sourceVersion,LocalDateTime sourceUpdatedAt) {
            this(id,chunkKey,chunkIndex,title,content,category,vector,signature,contentHash,tokenCount,sourceVersion,sourceUpdatedAt,null,null);
        }
    }
    public record KnowledgeVectorUpdate(Long id, String vector) {}
}
