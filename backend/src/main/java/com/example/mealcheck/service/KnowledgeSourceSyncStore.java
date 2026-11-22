package com.example.mealcheck.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Short database transactions only; no embedding calls while holding a lock. */
@Service
public class KnowledgeSourceSyncStore {
    public enum Reason { BUSY, REVIEW_REQUIRED, EDITED, EXPIRED }
    public static final class Conflict extends ResponseStatusException {
        private final Reason reason;
        public Conflict(Reason reason) {
            super(HttpStatus.CONFLICT, switch(reason) {
                case BUSY -> "该知识源正在另一项同步中，请稍后重试。";
                case REVIEW_REQUIRED -> "知识源版本或向量配置已变化，请由管理员确认后重建索引。";
                case EDITED -> "知识源在准备期间被修改，请检查人工编辑后由管理员重新同步。";
                case EXPIRED -> "知识源同步已失去执行租约，请重试。";
            });
            this.reason=reason;
        }
        public Reason reason() { return reason; }
    }
    public record Lease(String source,String fingerprint,String signature,String instanceId,UUID token,long revision,Long jobId,UUID jobToken) {
        public Lease(String source,String fingerprint,String signature,String instanceId,UUID token,long revision) { this(source,fingerprint,signature,instanceId,token,revision,null,null); }
    }
    public record Admission(Lease lease,int currentCount) { public boolean current() { return lease==null; } }
    private final JdbcTemplate jdbc;
    private final KnowledgeVectorPersistenceService persistence;
    private final int leaseSeconds;
    private final String instanceId=UUID.randomUUID().toString();
    public KnowledgeSourceSyncStore(JdbcTemplate jdbc,KnowledgeVectorPersistenceService persistence,
            @org.springframework.beans.factory.annotation.Value("${mealcheck.knowledge.source-sync-lease-seconds:120}") int leaseSeconds) {
        if(leaseSeconds<2 || leaseSeconds>600)throw new IllegalArgumentException("Knowledge source lease must be 2..600 seconds");
        this.jdbc=jdbc;this.persistence=persistence;this.leaseSeconds=leaseSeconds;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Admission claim(String source,String fingerprint,String signature,boolean explicit) {
        return claim(source,fingerprint,signature,explicit,null,null);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Admission claim(String source,String fingerprint,String signature,boolean explicit,Long expectedRevision,KnowledgeRebuildStore.Work work) {
        boundTransaction();
        jdbc.update("INSERT INTO knowledge_source_sync(source) VALUES (?) ON CONFLICT DO NOTHING",source);
        var row=jdbc.queryForMap("SELECT *,lease_until>clock_timestamp() AS live FROM knowledge_source_sync WHERE source=? FOR UPDATE",source);
        if(row.get("execution_token")!=null && Boolean.TRUE.equals(row.get("live")))throw new Conflict(Reason.BUSY);
        if(expectedRevision!=null&&((Number)row.get("data_revision")).longValue()!=expectedRevision) throw new Conflict(Reason.EDITED);
        if(work!=null) checkJob(work.id(),work.token());
        if(!explicit && row.get("completed_fingerprint")!=null) {
            if(!fingerprint.equals(row.get("completed_fingerprint")))throw new Conflict(Reason.REVIEW_REQUIRED);
            return new Admission(null,jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source=?",Integer.class,source));
        }
        if(!explicit && "EDITED".equals(row.get("last_error")))throw new Conflict(Reason.EDITED);
        if(!explicit && "REVIEW_REQUIRED".equals(row.get("last_error")))throw new Conflict(Reason.REVIEW_REQUIRED);
        var lease=new Lease(source,fingerprint,signature,instanceId,UUID.randomUUID(),((Number)row.get("data_revision")).longValue(),work==null?null:work.id(),work==null?null:work.token());
        jdbc.update("""
            UPDATE knowledge_source_sync SET target_fingerprint=?,embedding_signature=?,instance_id=?,execution_token=?,
              lease_until=clock_timestamp()+(? * INTERVAL '1 second'),last_error=NULL,updated_at=clock_timestamp() WHERE source=?
            """,fingerprint,signature,instanceId,lease.token(),leaseSeconds,source);
        return new Admission(lease,0);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void renew(Lease lease) {
        boundTransaction();
        if(lease.jobId()!=null) checkJob(lease.jobId(),lease.jobToken());
        if(jdbc.update("""
            UPDATE knowledge_source_sync SET lease_until=clock_timestamp()+(? * INTERVAL '1 second'),updated_at=clock_timestamp()
            WHERE source=? AND instance_id=? AND execution_token=? AND lease_until>clock_timestamp()
            """,leaseSeconds,lease.source(),lease.instanceId(),lease.token())!=1)throw new Conflict(Reason.EXPIRED);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
    public void publish(Lease lease,List<KnowledgeVectorPersistenceService.KnowledgeSyncRow> rows,List<Long> deletedIds) {
        publish(lease, rows, deletedIds, null);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
    public void publish(Lease lease,List<KnowledgeVectorPersistenceService.KnowledgeSyncRow> rows,List<Long> deletedIds,String splitterStrategy) {
        boundTransaction();
        // All chunk writers acquire a table write lock before their revision trigger runs.
        // Follow that order and exclude writes only during the short atomic publication.
        jdbc.execute("LOCK TABLE knowledge_chunks IN SHARE ROW EXCLUSIVE MODE");
        var row=jdbc.queryForMap("SELECT *,lease_until>clock_timestamp() AS live FROM knowledge_source_sync WHERE source=? FOR UPDATE",lease.source());
        if(!lease.token().equals(row.get("execution_token")) || !lease.instanceId().equals(row.get("instance_id"))
                || !Boolean.TRUE.equals(row.get("live")))throw new Conflict(Reason.EXPIRED);
        if(((Number)row.get("data_revision")).longValue()!=lease.revision())throw new Conflict(Reason.EDITED);
        if(lease.jobId()!=null) checkJob(lease.jobId(),lease.jobToken());
        persistence.syncSource(lease.source(),rows,deletedIds);
        jdbc.update("""
            UPDATE knowledge_source_sync SET completed_fingerprint=?,splitter_strategy=COALESCE(?,splitter_strategy),completed_at=clock_timestamp(),execution_token=NULL,
              lease_until=NULL,last_error=NULL,updated_at=clock_timestamp() WHERE source=?
            """,lease.fingerprint(),splitterStrategy,lease.source());
    }

    public String splitterStrategy(String source) {
        var rows = jdbc.queryForList("SELECT splitter_strategy FROM knowledge_source_sync WHERE source=?", String.class, source);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public void release(Lease lease,String error) {
        boundTransaction();
        jdbc.update("""
            UPDATE knowledge_source_sync SET execution_token=NULL,lease_until=NULL,last_error=?,updated_at=clock_timestamp()
            WHERE source=? AND instance_id=? AND execution_token=?
            """,error,lease.source(),lease.instanceId(),lease.token());
    }

    @Transactional(readOnly=true,timeout=5)
    public List<Map<String,Object>> status() {
        return jdbc.queryForList("""
            SELECT source,data_revision,completed_fingerprint,target_fingerprint,embedding_signature,completed_at,last_error,
              CASE WHEN execution_token IS NOT NULL THEN CASE WHEN lease_until>clock_timestamp() THEN 'RUNNING' ELSE 'INTERRUPTED' END
                WHEN last_error IS NOT NULL THEN 'FAILED' WHEN completed_fingerprint IS NOT NULL THEN 'SUCCEEDED' ELSE 'NOT_SYNCED' END AS state,
              lease_until,updated_at FROM knowledge_source_sync ORDER BY source
            """);
    }
    private void boundTransaction() {
        jdbc.execute("SET LOCAL idle_in_transaction_session_timeout='3s'");
        jdbc.execute("SET LOCAL lock_timeout='2s'");
    }
    private void checkJob(long id,UUID token) {
        if(jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET lease_until=clock_timestamp()+INTERVAL '3 minutes' WHERE id=? AND execution_token=? AND status='RUNNING' AND lease_until>clock_timestamp()",id,token)!=1)
            throw new Conflict(Reason.EXPIRED);
    }
}
