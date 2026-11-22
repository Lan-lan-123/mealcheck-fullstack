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
    public record Work(long id, String signature, int dimension, String storage, UUID token,
                       String mode, boolean sourceSynced, Long profileId, String splitterStrategy) {
        public Work(long id, String signature, int dimension, String storage, UUID token, String mode, boolean sourceSynced, Long profileId) {
            this(id, signature, dimension, storage, token, mode, sourceSynced, profileId, null);
        }
        public Work(long id, String signature, int dimension, String storage, UUID token, String mode, boolean sourceSynced) {
            this(id, signature, dimension, storage, token, mode, sourceSynced, null);
        }
        public Work(long id, String signature, int dimension, String storage, UUID token) {
            this(id, signature, dimension, storage, token, null, true);
        }
    }
    public record Chunk(long id, String title, String content, String hash) { }

    @Transactional
    public long request(String signature, int dimension, String storage) {
        validateTarget(dimension, storage);
        // Serialize creation/retry without holding a session lock during model calls.
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var active = jdbc.queryForList("SELECT id,signature,scope FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')");
        if (!active.isEmpty()) {
            if (!signature.equals(active.get(0).get("signature")) || !"ALL".equals(active.get(0).get("scope"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "另一个向量任务正在运行");
            return ((Number) active.get(0).get("id")).longValue();
        }
        var failed = jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs WHERE signature=? AND scope='ALL' AND status='FAILED' ORDER BY id DESC LIMIT 1", signature);
        if (!failed.isEmpty()) return ((Number) failed.get(0).get("id")).longValue();
        long id=jdbc.queryForObject("INSERT INTO knowledge_embedding_rebuild_jobs(signature,dimension,storage) VALUES (?,?,?) RETURNING id",
                Long.class, signature, dimension, storage);
        event(id,"SUBMITTED","自动向量维护任务已创建。");
        return id;
    }

    @Transactional
    public long requestBuild(String signature, int dimension, String storage, String mode) {
        return requestBuild(signature, dimension, storage, mode, publishedSplitterStrategy());
    }

    private String publishedSplitterStrategy() {
        var rows = jdbc.queryForList("SELECT splitter_strategy FROM knowledge_source_sync WHERE source='diet_guides.md'", String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public long requestBuild(String signature, int dimension, String storage, String mode, String splitterStrategy) {
        validateTarget(dimension, storage);
        if (splitterStrategy != null) splitterStrategy = MarkdownKnowledgeChunker.normalizeStrategy(splitterStrategy);
        if (!List.of("LOCAL", "SEMANTIC").contains(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知向量方式");
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var active = jdbc.queryForList("SELECT id,signature,embedding_mode,scope,splitter_strategy,profile_id FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')");
        if (!active.isEmpty()) {
            var row = active.get(0);
            if (!signature.equals(row.get("signature")) || !mode.equals(row.get("embedding_mode")) || !"ALL".equals(row.get("scope"))
                    || !java.util.Objects.equals(splitterStrategy, row.get("splitter_strategy")) || row.get("profile_id") != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已有知识库重建任务，请等待完成后再切换方式。");
            return ((Number) row.get("id")).longValue();
        }
        var failed = jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs WHERE signature=? AND embedding_mode=? AND splitter_strategy IS NOT DISTINCT FROM CAST(? AS varchar) AND profile_id IS NULL AND scope='ALL' AND status='FAILED' ORDER BY id DESC LIMIT 1",
                Long.class, signature, mode, splitterStrategy);
        if (!failed.isEmpty()) {
            long id = failed.get(0);
            jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='RETRY',attempts=0,execution_token=NULL,lease_until=NULL,last_error=NULL,failure_summary=NULL,failure_retryable=NULL,finished_at=NULL,next_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            event(id,"MANUAL_RETRY","重新提交匹配配置的任务，继续有效暂存进度。");
            return id;
        }
        long id=jdbc.queryForObject("""
                INSERT INTO knowledge_embedding_rebuild_jobs(signature,dimension,storage,embedding_mode,source_synced,splitter_strategy)
                VALUES (?,?,?,?,FALSE,?) RETURNING id
                """, Long.class, signature, dimension, storage, mode, splitterStrategy);
        event(id,"SUBMITTED","整库向量任务已创建，等待同步知识源。");
        return id;
    }

    public Map<String,Object> latestBuild() {
        var ids = jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs ORDER BY id DESC LIMIT 1", Long.class);
        return ids.isEmpty() ? null : status(ids.get(0));
    }

    @Transactional
    public long requestProfileBuild(String signature, int dimension, String storage, long profileId) {
        return requestProfileBuild(signature, dimension, storage, profileId, publishedSplitterStrategy());
    }

    @Transactional
    public long requestProfileBuild(String signature, int dimension, String storage, long profileId, String splitterStrategy) {
        validateTarget(dimension,storage);
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var active=jdbc.queryForList("SELECT id,profile_id,signature,splitter_strategy FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')");
        if (!active.isEmpty()) {
            var row=active.get(0);
            if (row.get("profile_id") instanceof Number profile && profile.longValue()==profileId && signature.equals(row.get("signature"))
                    && java.util.Objects.equals(splitterStrategy,row.get("splitter_strategy")))
                return ((Number)row.get("id")).longValue();
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有向量任务运行中，请等待完成或终止后再应用配置。");
        }
        long id=jdbc.queryForObject("INSERT INTO knowledge_embedding_rebuild_jobs(signature,dimension,storage,embedding_mode,source_synced,profile_id,splitter_strategy) VALUES (?,?,?,'SEMANTIC',FALSE,?,?) RETURNING id",
                Long.class,signature,dimension,storage,profileId,splitterStrategy);
        event(id,"SUBMITTED","已固定向量配置版本，整库重建完成后统一生效。");
        return id;
    }

    @Transactional
    public void invalidateIncompatible(String local, String semantic, String current) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var active=jdbc.queryForList("SELECT id,signature,scope,profile_id FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY') FOR UPDATE");
        for (var row:active) {
            if (row.get("profile_id")!=null) continue; // Immutable profile survives subsequent draft edits.
            String signature=(String)row.get("signature");
            boolean compatible=signature.equals(local) || signature.equals(semantic);
            if ("SELECTED".equals(row.get("scope"))) compatible=compatible && signature.equals(current);
            if (compatible) continue;
            long id=((Number)row.get("id")).longValue();
            String code=signature.startsWith("semantic-v1:") && semantic==null ? "CONFIG_UNAVAILABLE" : "CONFIG_CHANGED";
            String summary=semantic==null && signature.startsWith("semantic-v1:")
                    ? "向量模型配置不可用，任务已停止；配置恢复后可按当前配置重新提交。"
                    : "模型、接口、维度或生效方式已变化，旧任务已停止；请按当前配置重新提交。";
            jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='INVALIDATED',last_error=?,failure_summary=?,failure_retryable=FALSE,execution_token=NULL,lease_until=NULL,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",code,summary,id);
            event(id,code,summary);
        }
    }

    @Transactional
    public void cancel(long id) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        if (jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='CANCELLED',execution_token=NULL,lease_until=NULL,last_error='ADMIN_CANCELLED',failure_summary='管理员已终止任务，原向量继续生效。',failure_retryable=FALSE,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','RUNNING','RETRY')",id)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"任务已结束或不存在，请刷新任务历史。");
        event(id,"ADMIN_CANCELLED","管理员已终止任务，原向量继续生效。");
    }

    public boolean canContinue(Work work) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_embedding_rebuild_jobs WHERE id=? AND execution_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP)",Boolean.class,work.id(),work.token()));
    }

    public List<Long> targets(long id) {
        return jdbc.queryForList("SELECT chunk_id FROM knowledge_embedding_rebuild_targets WHERE job_id=? ORDER BY chunk_id",Long.class,id);
    }

    public Map<String,Object> history(int page,int size) {
        int safeSize=Math.max(1,Math.min(size,50));
        int safePage=Math.max(0,Math.min(page,100000));
        var ids=jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs ORDER BY id DESC LIMIT ? OFFSET ?",Long.class,safeSize,(long)safePage*safeSize);
        long total=jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs",Long.class);
        return Map.of("content",ids.stream().map(this::status).toList(),"totalElements",total,"page",safePage,"size",safeSize,"totalPages",(total+safeSize-1)/safeSize);
    }

    public Map<String,Object> details(long id) {
        var result=new java.util.LinkedHashMap<>(status(id));
        result.put("events",jdbc.queryForList("SELECT code,summary,created_at FROM knowledge_embedding_job_events WHERE job_id=? ORDER BY id DESC LIMIT 50",id));
        result.put("chunkIds",targets(id));
        return result;
    }

    private void event(long id,String code,String summary) {
        jdbc.update("INSERT INTO knowledge_embedding_job_events(job_id,code,summary) VALUES (?,?,?)",id,code,summary);
    }

    /** Read actual stored signatures; configured provider alone does not prove a row is ready. */
    public Map<String,Object> inventory(String activeSignature) {
        var result = new java.util.LinkedHashMap<String,Object>();
        var counts = jdbc.queryForMap("""
                SELECT count(*) AS total,
                  count(*) FILTER (WHERE embedding_signature LIKE 'hash-v1:%') AS local,
                  count(*) FILTER (WHERE embedding_signature LIKE 'semantic-v1:%') AS semantic,
                  count(*) FILTER (WHERE embedding_signature=?) AS compatible
                FROM knowledge_chunks
                """, activeSignature);
        long total = ((Number)counts.get("total")).longValue();
        counts.put("unknown", total - ((Number)counts.get("local")).longValue() - ((Number)counts.get("semantic")).longValue());
        counts.put("outdated", total - ((Number)counts.get("compatible")).longValue());
        result.put("counts", counts);
        var chunks = jdbc.queryForList("""
                SELECT k.id,k.title,k.source,k.embedding_signature AS signature,k.token_count AS estimated_tokens,
                  k.embedding_input_tokens,k.embedding_total_tokens,
                  vector_dims(k.embedding) AS dimension,k.embedding_updated_at AS generated_at,
                  j.id AS job_id,j.status AS job_status,
                  CASE WHEN i.input_hash=md5(coalesce(k.title,'') || E'\\n\\n' || k.content) THEN TRUE ELSE FALSE END AS staged
                FROM knowledge_chunks k
                LEFT JOIN knowledge_embedding_rebuild_jobs j ON j.status IN ('PENDING','RUNNING','RETRY')
                  AND (j.scope='ALL' OR EXISTS (SELECT 1 FROM knowledge_embedding_rebuild_targets t WHERE t.job_id=j.id AND t.chunk_id=k.id))
                LEFT JOIN knowledge_embedding_rebuild_items i ON i.job_id=j.id AND i.chunk_id=k.id
                ORDER BY k.id LIMIT 2000
                """);
        for (var row : chunks) {
            String signature = (String) row.get("signature");
            boolean local = signature != null && signature.startsWith("hash-v1:");
            boolean semantic = signature != null && signature.startsWith("semantic-v1:");
            row.put("mode", local ? "LOCAL" : semantic ? "SEMANTIC" : "UNKNOWN");
            String[] parts=semantic ? signature.split(":",-1) : new String[0];
            String model=parts.length>=5 ? String.join(":",java.util.Arrays.copyOfRange(parts,1,parts.length-3))
                    : parts.length>1 ? parts[1] : null;
            row.put("model", semantic ? model : local ? "HashEmbeddingService" : null);
            row.put("compatible", activeSignature.equals(signature));
        }
        result.put("chunks", chunks);
        result.put("truncated", total > chunks.size());
        return result;
    }

    @Transactional
    public long requestSelected(String signature, int dimension, String storage, String mode, List<Long> ids) {
        validateTarget(dimension, storage);
        if (mode == null || !List.of("LOCAL", "SEMANTIC").contains(mode) || ids == null || ids.isEmpty()
                || ids.size() > 500 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 1～500 条有效知识。");
        var selected = ids.stream().distinct().sorted().toList();
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        var active = jdbc.queryForList("SELECT id,signature,scope FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')");
        if (!active.isEmpty()) {
            var row = active.get(0);
            long id = ((Number) row.get("id")).longValue();
            if (signature.equals(row.get("signature")) && "SELECTED".equals(row.get("scope"))
                    && selected.equals(jdbc.queryForList("SELECT chunk_id FROM knowledge_embedding_rebuild_targets WHERE job_id=? ORDER BY chunk_id", Long.class, id))) return id;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有向量任务运行中，请等待完成。");
        }
        // Keep deletion and activation out until the selected IDs and active profile are captured.
        jdbc.execute("LOCK TABLE knowledge_chunks IN ROW EXCLUSIVE MODE");
        String columnType = jdbc.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='knowledge_chunks'::regclass AND attname='embedding' AND NOT attisdropped", String.class);
        if (!(storage + "(" + dimension + ")").equals(columnType))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "向量存储或维度已变化，请先整库重建。");
        String persisted = jdbc.queryForObject("SELECT signature FROM knowledge_embedding_settings WHERE id=1 FOR SHARE", String.class);
        if (persisted != null && !signature.equals(persisted))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "生效模型已变化，请刷新后重试。");
        for (Long id : selected) {
            if (jdbc.queryForList("SELECT id FROM knowledge_chunks WHERE id=? FOR KEY SHARE", Long.class, id).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "部分知识已删除，请刷新后重新选择。");
        }
        var failed = jdbc.queryForList("SELECT id FROM knowledge_embedding_rebuild_jobs WHERE signature=? AND scope='SELECTED' AND status='FAILED' ORDER BY id DESC LIMIT 20", Long.class, signature);
        for (long id : failed) {
            if (selected.equals(jdbc.queryForList("SELECT chunk_id FROM knowledge_embedding_rebuild_targets WHERE job_id=? ORDER BY chunk_id", Long.class, id))) {
                jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='RETRY',attempts=0,execution_token=NULL,lease_until=NULL,last_error=NULL,failure_summary=NULL,failure_retryable=NULL,finished_at=NULL,next_attempt_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
                event(id,"MANUAL_RETRY","重新提交选中知识任务，继续有效暂存进度。");
                return id;
            }
        }
        long job = jdbc.queryForObject("""
                INSERT INTO knowledge_embedding_rebuild_jobs(signature,dimension,storage,embedding_mode,source_synced,scope)
                VALUES (?,?,?,?,TRUE,'SELECTED') RETURNING id
                """, Long.class, signature, dimension, storage, mode);
        jdbc.batchUpdate("INSERT INTO knowledge_embedding_rebuild_targets(job_id,chunk_id) VALUES (?,?)",
                selected.stream().map(id -> new Object[]{job,id}).toList());
        event(job,"SUBMITTED","选中知识向量任务已创建，共 "+selected.size()+" 条。");
        return job;
    }

    @Transactional
    public Work claim(String configuredSignature) {
        return claimMatching(configuredSignature, configuredSignature);
    }

    @Transactional
    public Work claimRequested(String localSignature, String semanticSignature) {
        return claimMatching(localSignature, semanticSignature);
    }

    private Work claimMatching(String firstSignature, String secondSignature) {
        var rows = jdbc.queryForList("""
                SELECT * FROM knowledge_embedding_rebuild_jobs WHERE (signature IN (?,?) OR profile_id IS NOT NULL) AND next_attempt_at<=CURRENT_TIMESTAMP
                  AND (status IN ('PENDING','RETRY') OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, firstSignature, secondSignature);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        if (((Number) row.get("attempts")).intValue() >= 5) {
            jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='FAILED',last_error='ATTEMPTS_EXHAUSTED',failure_summary='任务已达到重试上限，请检查配置后手动重试。',failure_retryable=TRUE,execution_token=NULL,lease_until=NULL,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            event(id,"ATTEMPTS_EXHAUSTED","任务已达到重试上限，请检查配置后手动重试。");
            return null;
        }
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='RUNNING',attempts=attempts+1,execution_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=?", token, id);
        if (((Number)row.get("attempts")).intValue()==0 && row.get("last_error")==null)event(id,"STARTED","开始处理向量任务。");
        if (row.get("last_error")!=null) event(id,"RETRY_STARTED","开始重试，继续使用有效暂存进度。");
        return new Work(id, row.get("signature").toString(), ((Number) row.get("dimension")).intValue(),
                row.get("storage").toString(), token, (String) row.get("embedding_mode"), Boolean.TRUE.equals(row.get("source_synced")),
                row.get("profile_id") instanceof Number profile ? profile.longValue() : null, (String) row.get("splitter_strategy"));
    }

    @Transactional
    public boolean sourceSynced(Work work) {
        if (!ownsLease(work)) return false;
        return jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET source_synced=TRUE,lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=? AND execution_token=?",
                work.id(), work.token()) == 1;
    }

    public List<Chunk> pending(Work work, int limit) {
        return jdbc.query("SELECT k.id,k.title,k.content," + HASH + " AS input_hash FROM knowledge_chunks k "
                + "LEFT JOIN knowledge_embedding_rebuild_items i ON i.job_id=? AND i.chunk_id=k.id "
                + "WHERE ((SELECT scope FROM knowledge_embedding_rebuild_jobs WHERE id=?)='ALL' OR EXISTS "
                + "(SELECT 1 FROM knowledge_embedding_rebuild_targets t WHERE t.job_id=? AND t.chunk_id=k.id)) "
                + "AND (i.chunk_id IS NULL OR i.input_hash<>" + HASH + ") ORDER BY k.id LIMIT ?",
                (rs, n) -> new Chunk(rs.getLong("id"), rs.getString("title"), rs.getString("content"), rs.getString("input_hash")), work.id(), work.id(), work.id(), limit);
    }

    @Transactional
    public boolean stage(Work work, Chunk chunk, String vector) {
        return stage(work, chunk, vector, null, null);
    }

    @Transactional
    public boolean stage(Work work, Chunk chunk, String vector, Long inputTokens, Long totalTokens) {
        if (!ownsLease(work)) return false;
        jdbc.update("INSERT INTO knowledge_embedding_rebuild_items(job_id,chunk_id,input_hash,vector_text,embedding_input_tokens,embedding_total_tokens) "
                + "SELECT ?,k.id,?,?,?,? FROM knowledge_chunks k WHERE k.id=? AND " + HASH + "=? "
                + "ON CONFLICT(job_id,chunk_id) DO UPDATE SET input_hash=EXCLUDED.input_hash,vector_text=EXCLUDED.vector_text,"
                + "embedding_input_tokens=EXCLUDED.embedding_input_tokens,embedding_total_tokens=EXCLUDED.embedding_total_tokens",
                work.id(), chunk.hash(), vector, inputTokens, totalTokens, chunk.id(), chunk.hash());
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
        if (work.mode() != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT source_synced FROM knowledge_embedding_rebuild_jobs WHERE id=?", Boolean.class, work.id()))) return false;
        if (!pending(work, 1).isEmpty()) return false;
        String type = work.storage() + "(" + work.dimension() + ")";
        boolean selected = "SELECTED".equals(jdbc.queryForObject("SELECT scope FROM knowledge_embedding_rebuild_jobs WHERE id=?", String.class, work.id()));
        if (selected) {
            String current = jdbc.queryForObject("SELECT signature FROM knowledge_embedding_settings WHERE id=1 FOR SHARE", String.class);
            if (current != null && !current.equals(work.signature()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "生效模型已变化，无法更新选中向量。");
            jdbc.update("UPDATE knowledge_chunks k SET embedding=CAST(i.vector_text AS " + type + "),embedding_signature=?,embedding_updated_at=CURRENT_TIMESTAMP,"
                    + "embedding_input_tokens=i.embedding_input_tokens,embedding_total_tokens=i.embedding_total_tokens "
                    + "FROM knowledge_embedding_rebuild_items i JOIN knowledge_embedding_rebuild_targets t ON t.job_id=i.job_id AND t.chunk_id=i.chunk_id "
                    + "WHERE i.job_id=? AND i.chunk_id=k.id", work.signature(), work.id());
        } else {
            jdbc.execute("ALTER TABLE knowledge_chunks ADD COLUMN embedding_next " + type);
            jdbc.update("UPDATE knowledge_chunks k SET embedding_next=CAST(i.vector_text AS " + type + "),"
                    + "embedding_input_tokens=i.embedding_input_tokens,embedding_total_tokens=i.embedding_total_tokens "
                    + "FROM knowledge_embedding_rebuild_items i WHERE i.job_id=? AND i.chunk_id=k.id", work.id());
            jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding_next SET NOT NULL");
            jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
            jdbc.execute("ALTER TABLE knowledge_chunks DROP COLUMN embedding");
            jdbc.execute("ALTER TABLE knowledge_chunks RENAME COLUMN embedding_next TO embedding");
            jdbc.update("UPDATE knowledge_chunks SET embedding_signature=?,embedding_updated_at=CURRENT_TIMESTAMP", work.signature());
            jdbc.execute("CREATE INDEX idx_knowledge_chunks_embedding ON knowledge_chunks USING ivfflat (embedding "
                    + work.storage() + "_cosine_ops) WITH (lists=100)");
            if (work.mode() != null) {
                jdbc.update("UPDATE knowledge_embedding_settings SET mode=?,signature=?,active_profile_id=COALESCE(?,active_profile_id),configuration_version=configuration_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                        work.mode(), work.signature(),work.profileId());
                jdbc.update("UPDATE knowledge_cache_state SET revision=revision+1 WHERE id=1");
            } else {
                jdbc.update("UPDATE knowledge_embedding_settings SET signature=?,updated_at=CURRENT_TIMESTAMP WHERE id=1 AND mode IS NOT NULL",
                        work.signature());
            }
        }
        long completed = selected ? jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_targets WHERE job_id=?", Long.class, work.id())
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Long.class);
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='SUCCEEDED',completed_chunks=?,last_error=NULL,execution_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", completed, work.id());
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET failure_summary=NULL,failure_retryable=NULL,finished_at=CURRENT_TIMESTAMP WHERE id=?",work.id());
        event(work.id(),"SUCCEEDED","向量生成与发布完成，共 "+completed+" 条。");
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
    public void fail(Work work, KnowledgeJobFailure failure) {
        int changed=jdbc.update("""
                UPDATE knowledge_embedding_rebuild_jobs SET status=CASE WHEN ? AND attempts<5 THEN 'RETRY' ELSE 'FAILED' END,
                  last_error=?,failure_summary=?,failure_retryable=?,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',
                  execution_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP,
                  finished_at=CASE WHEN ? AND attempts<5 THEN NULL ELSE CURRENT_TIMESTAMP END
                WHERE id=? AND execution_token=? AND status='RUNNING'
                """,failure.retryable(),failure.code(),failure.summary(),failure.retryable(),failure.retryable(),work.id(),work.token());
        if(changed==1) event(work.id(),failure.code(),failure.summary());
    }

    @Transactional
    public void retry(long id, String configuredSignature) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302020)");
        if (jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')", Long.class) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有向量重建任务运行中");
        if (jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET status='RETRY',attempts=0,execution_token=NULL,lease_until=NULL,last_error=NULL,next_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND signature=? AND status='FAILED'", id, configuredSignature) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态或当前向量配置不匹配");
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET failure_summary=NULL,failure_retryable=NULL,finished_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        event(id,"MANUAL_RETRY","管理员已重试任务，继续使用有效暂存进度。");
    }

    public Map<String,Object> status(long id) {
        var rows = jdbc.queryForList("SELECT id,signature,dimension,storage,status,attempts,last_error,failure_summary,failure_retryable,finished_at,next_attempt_at,embedding_mode,source_synced,scope,completed_chunks,profile_id,splitter_strategy,created_at,updated_at FROM knowledge_embedding_rebuild_jobs WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "向量重建任务不存在");
        var result = new java.util.LinkedHashMap<>(rows.get(0));
        result.put("staged", jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_items i JOIN knowledge_chunks k ON k.id=i.chunk_id WHERE i.job_id=? AND i.input_hash="+HASH, Long.class, id));
        result.put("totalChunks", result.get("completed_chunks") != null ? result.get("completed_chunks") : "SELECTED".equals(result.get("scope"))
                ? jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_targets WHERE job_id=?", Long.class, id)
                : jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks", Long.class));
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
