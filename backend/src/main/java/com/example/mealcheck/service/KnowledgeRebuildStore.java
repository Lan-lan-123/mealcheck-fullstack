package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable staging: remote embedding calls happen outside these transactions. */
@Service
public class KnowledgeRebuildStore {
    private static final String HASH = "md5(coalesce(k.title,'') || E'\\n\\n' || k.content)";
    private final JdbcTemplate jdbc;
    public KnowledgeRebuildStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Work(long id, String signature, int dimension, String storage, UUID token) { }
    public record Chunk(long id, String title, String content, String hash) { }

    @Transactional
    public long request(String signature, int dimension, String storage) {
        validateTarget(dimension, storage);
        // Serialize creation/retry without holding a session lock during model calls.
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var active = jdbc.queryForList("SELECT id,signature FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')");
        if (!active.isEmpty()) {
            if (!signature.equals(active.get(0).get("signature"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "另一个向量版本正在重建");
            return ((Number) active.get(0).get("id")).longValue();
        }
        var failed = jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs WHERE signature=? AND status='FAILED' ORDER BY id DESC LIMIT 1", signature);
        if (!failed.isEmpty()) return ((Number) failed.get(0).get("id")).longValue();
        return jdbc.queryForObject("INSERT INTO knowledge_embedding_rebuild_jobs(signature,dimension,storage) VALUES (?,?,?) RETURNING id",
                Long.class, signature, dimension, storage);
    }

    @Transactional
    public Work claim(String configuredSignature) {
        var rows = jdbc.queryForList("""
                SELECT * FROM knowledge_embedding_rebuild_jobs WHERE signature=? AND next_attempt_at<=CURRENT_TIMESTAMP
                  AND (status IN ('PENDING','RETRY') OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, configuredSignature);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        if (((Number) row.get("attempts")).intValue() >= 5) {
            jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='FAILED',last_error='ATTEMPTS_EXHAUSTED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            return null;
        }
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='RUNNING',attempts=attempts+1,execution_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=?", token, id);
        return new Work(id, row.get("signature").toString(), ((Number) row.get("dimension")).intValue(), row.get("storage").toString(), token);
    }

    public List<Chunk> pending(Work work, int limit) {
        return jdbc.query("SELECT k.id,k.title,k.content," + HASH + " AS input_hash FROM knowledge_chunks k "
                + "LEFT JOIN knowledge_embedding_rebuild_items i ON i.job_id=? AND i.chunk_id=k.id "
                + "WHERE i.chunk_id IS NULL OR i.input_hash<>" + HASH + " ORDER BY k.id LIMIT ?",
                (rs, n) -> new Chunk(rs.getLong("id"), rs.getString("title"), rs.getString("content"), rs.getString("input_hash")), work.id(), limit);
    }

    @Transactional
    public boolean stage(Work work, Chunk chunk, String vector) {
        if (!ownsLease(work)) return false;
        jdbc.update("INSERT INTO knowledge_embedding_rebuild_items(job_id,chunk_id,input_hash,vector_text) "
                + "SELECT ?,k.id,?,? FROM knowledge_chunks k WHERE k.id=? AND " + HASH + "=? "
                + "ON CONFLICT(job_id,chunk_id) DO UPDATE SET input_hash=EXCLUDED.input_hash,vector_text=EXCLUDED.vector_text",
                work.id(), chunk.hash(), vector, chunk.id(), chunk.hash());
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=? AND execution_token=?", work.id(), work.token());
        return true;
    }

    @Transactional(timeout = 180)
    public boolean activate(Work work) {
        validateTarget(work.dimension(), work.storage());
        if (!ownsLease(work)) return false;
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        // Block edits only for the local DB switch. All model work is already staged.
        jdbc.execute("LOCK TABLE knowledge_chunks IN SHARE ROW EXCLUSIVE MODE");
        if (!pending(work, 1).isEmpty()) return false;
        String type = work.storage() + "(" + work.dimension() + ")";
        jdbc.execute("ALTER TABLE knowledge_chunks ADD COLUMN embedding_next " + type);
        jdbc.update("UPDATE knowledge_chunks k SET embedding_next=CAST(i.vector_text AS " + type + ") "
                + "FROM knowledge_embedding_rebuild_items i WHERE i.job_id=? AND i.chunk_id=k.id", work.id());
        jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding_next SET NOT NULL");
        jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks DROP COLUMN embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks RENAME COLUMN embedding_next TO embedding");
        jdbc.update("UPDATE knowledge_chunks SET embedding_signature=?", work.signature());
        jdbc.execute("CREATE INDEX idx_knowledge_chunks_embedding ON knowledge_chunks USING ivfflat (embedding "
                + work.storage() + "_cosine_ops) WITH (lists=100)");
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='SUCCEEDED',last_error=NULL,execution_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", work.id());
        jdbc.update("DELETE FROM knowledge_embedding_rebuild_items WHERE job_id=?", work.id());
        return true;
    }

    @Transactional
    public void release(Work work, boolean failed) {
        jdbc.update("""
                UPDATE knowledge_embedding_rebuild_jobs SET status=CASE WHEN ? AND attempts>=5 THEN 'FAILED' ELSE 'RETRY' END,
                  attempts=attempts-CASE WHEN ? THEN 0 ELSE 1 END,last_error=CASE WHEN ? THEN 'EMBEDDING_REBUILD_FAILED' ELSE NULL END,
                  next_attempt_at=CURRENT_TIMESTAMP+(CASE WHEN ? THEN 30 ELSE 1 END * INTERVAL '1 second'),
                  execution_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND execution_token=? AND status='RUNNING'
                """, failed, failed, failed, failed, work.id(), work.token());
    }

    @Transactional
    public void retry(long id, String configuredSignature) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        if (jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')", Long.class) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有向量重建任务运行中");
        if (jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='RETRY',attempts=0,execution_token=NULL,lease_until=NULL,last_error=NULL,next_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND signature=? AND status='FAILED'", id, configuredSignature) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态或当前向量配置不匹配");
    }

    public Map<String,Object> status(long id) {
        var rows = jdbc.queryForList("SELECT id,signature,dimension,storage,status,attempts,last_error,created_at,updated_at FROM knowledge_embedding_rebuild_jobs WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "向量重建任务不存在");
        var result = new java.util.LinkedHashMap<>(rows.get(0));
        result.put("staged", jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_items WHERE job_id=?", Long.class, id));
        result.put("totalChunks", jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Long.class));
        return result;
    }

    private boolean ownsLease(Work work) {
        return !jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs WHERE id=? AND execution_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP FOR UPDATE", work.id(), work.token()).isEmpty();
    }
    private void validateTarget(int dimension, String storage) {
        if (dimension < 1 || dimension > 2000 || !("vector".equals(storage) || "halfvec".equals(storage)))
            throw new IllegalArgumentException("向量存储类型或维度无效");
    }
}
