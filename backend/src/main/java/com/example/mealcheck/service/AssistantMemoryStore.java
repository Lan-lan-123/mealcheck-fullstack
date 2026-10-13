package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Text and versioned vectors are independent. All remote work happens outside transactions. */
@Service
public class AssistantMemoryStore {
    private final JdbcTemplate jdbc;
    public AssistantMemoryStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Work(long id,String signature,int dimension,String storage,UUID token) { }
    public record Fact(long id,String text,String hash) { }

    public boolean ready(String signature) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT signature=? FROM assistant_memory_version WHERE id=1",Boolean.class,signature));
    }
    public boolean needsRebuild(String signature) {
        return !ready(signature) || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM assistant_memory_facts f LEFT JOIN assistant_memory_vectors v
                  ON v.fact_id=f.id AND v.signature=? WHERE v.fact_id IS NULL OR v.input_hash<>f.fact_hash)
                """,Boolean.class,signature));
    }
    @Transactional
    public long saveText(long userId,long conversationId,String type,String text,String hash,double confidence) {
        return jdbc.queryForObject("""
                INSERT INTO assistant_memory_facts(user_id,conversation_id,fact_type,fact_text,fact_hash,confidence)
                VALUES (?,?,?,?,?,?) ON CONFLICT(user_id,fact_hash) DO UPDATE SET
                  conversation_id=EXCLUDED.conversation_id,fact_type=EXCLUDED.fact_type,
                  confidence=LEAST(1.0,assistant_memory_facts.confidence+0.05),last_seen_at=CURRENT_TIMESTAMP
                RETURNING id
                """,Long.class,userId,conversationId,type,text,hash,confidence);
    }
    @Transactional
    public void saveVector(long id,String hash,String signature,int dimension,String storage,String vector) {
        validate(dimension,storage);
        String current=jdbc.queryForObject("SELECT signature FROM assistant_memory_version WHERE id=1 FOR SHARE",String.class);
        if (!signature.equals(current)) return;
        String column="halfvec".equals(storage)?"embedding_half":"embedding";
        jdbc.update("INSERT INTO assistant_memory_vectors(fact_id,signature,input_hash,dimension,"+column+") "
                +"SELECT id,?,?,?,CAST(? AS "+storage+"("+dimension+")) FROM assistant_memory_facts WHERE id=? AND fact_hash=? "
                +"ON CONFLICT(fact_id,signature) DO UPDATE SET input_hash=EXCLUDED.input_hash,"+column+"=EXCLUDED."+column,
                signature,hash,dimension,vector,id,hash);
    }
    @Transactional
    public long request(String signature,int dimension,String storage) {
        validate(dimension,storage);
        jdbc.execute("SELECT pg_advisory_xact_lock(7302022)");
        var active=jdbc.queryForList("SELECT id,signature FROM assistant_memory_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')");
        if (!active.isEmpty()) {
            if (!signature.equals(active.get(0).get("signature"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"另一个记忆向量版本正在重建");
            return ((Number)active.get(0).get("id")).longValue();
        }
        var failed=jdbc.queryForList("SELECT id FROM assistant_memory_rebuild_jobs WHERE signature=? AND status='FAILED' ORDER BY id DESC LIMIT 1",signature);
        if (!failed.isEmpty()) return ((Number)failed.get(0).get("id")).longValue();
        long id=jdbc.queryForObject("INSERT INTO assistant_memory_rebuild_jobs(signature,dimension,storage) VALUES (?,?,?) RETURNING id",Long.class,signature,dimension,storage);
        jdbc.update("""
                INSERT INTO assistant_memory_rebuild_items(job_id,fact_id,input_hash,vector_text)
                SELECT ?,f.id,f.fact_hash,coalesce(v.embedding,v.embedding_half::vector)::text
                FROM assistant_memory_facts f JOIN assistant_memory_vectors v ON v.fact_id=f.id AND v.input_hash=f.fact_hash
                WHERE v.signature=?
                """,id,signature);
        return id;
    }
    @Transactional
    public Work claim(String signature) {
        var rows=jdbc.queryForList("""
                SELECT * FROM assistant_memory_rebuild_jobs WHERE signature=? AND next_attempt_at<=CURRENT_TIMESTAMP
                  AND (status IN ('PENDING','RETRY') OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP))
                ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,signature);
        if (rows.isEmpty()) return null;
        var row=rows.get(0);long id=((Number)row.get("id")).longValue();
        if (((Number)row.get("attempts")).intValue()>=5) {
            jdbc.update("UPDATE assistant_memory_rebuild_jobs SET status='FAILED',last_error='ATTEMPTS_EXHAUSTED',execution_token=NULL,lease_until=NULL WHERE id=?",id);
            return null;
        }
        UUID token=UUID.randomUUID();
        jdbc.update("UPDATE assistant_memory_rebuild_jobs SET status='RUNNING',attempts=attempts+1,execution_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=?",token,id);
        return new Work(id,signature,((Number)row.get("dimension")).intValue(),row.get("storage").toString(),token);
    }
    public List<Fact> pending(Work work,int limit) {
        return jdbc.query("""
                SELECT f.id,f.fact_text,f.fact_hash FROM assistant_memory_facts f
                LEFT JOIN assistant_memory_rebuild_items i ON i.job_id=? AND i.fact_id=f.id
                WHERE i.fact_id IS NULL OR i.input_hash<>f.fact_hash ORDER BY f.id LIMIT ?
                """,(rs,n)->new Fact(rs.getLong(1),rs.getString(2),rs.getString(3)),work.id(),limit);
    }
    @Transactional
    public boolean stage(Work work,Fact fact,String vector) {
        if (!owns(work)) return false;
        jdbc.update("""
                INSERT INTO assistant_memory_rebuild_items(job_id,fact_id,input_hash,vector_text)
                SELECT ?,id,?,? FROM assistant_memory_facts WHERE id=? AND fact_hash=?
                ON CONFLICT(job_id,fact_id) DO UPDATE SET input_hash=EXCLUDED.input_hash,vector_text=EXCLUDED.vector_text
                """,work.id(),fact.hash(),vector,fact.id(),fact.hash());
        jdbc.update("UPDATE assistant_memory_rebuild_jobs SET lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',updated_at=CURRENT_TIMESTAMP WHERE id=?",work.id());
        return true;
    }
    @Transactional(timeout=30)
    public boolean activate(Work work) {
        validate(work.dimension(),work.storage());
        if (!owns(work)) return false;
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.queryForObject("SELECT id FROM assistant_memory_version WHERE id=1 FOR UPDATE",Integer.class);
        jdbc.execute("LOCK TABLE assistant_memory_facts IN SHARE ROW EXCLUSIVE MODE");
        if (!pending(work,1).isEmpty()) return false;
        String column="halfvec".equals(work.storage())?"embedding_half":"embedding";
        jdbc.update("DELETE FROM assistant_memory_vectors WHERE signature=?",work.signature());
        jdbc.update("INSERT INTO assistant_memory_vectors(fact_id,signature,input_hash,dimension,"+column+") "
                +"SELECT fact_id,?,input_hash,?,CAST(vector_text AS "+work.storage()+"("+work.dimension()+")) FROM assistant_memory_rebuild_items WHERE job_id=?",
                work.signature(),work.dimension(),work.id());
        jdbc.update("UPDATE assistant_memory_version SET signature=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",work.signature());
        jdbc.update("DELETE FROM assistant_memory_vectors WHERE signature<>?",work.signature());
        jdbc.update("UPDATE assistant_memory_rebuild_jobs SET status='SUCCEEDED',execution_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",work.id());
        jdbc.update("DELETE FROM assistant_memory_rebuild_items WHERE job_id=?",work.id());
        return true;
    }
    @Transactional
    public void release(Work work,boolean failed) {
        jdbc.update("""
                UPDATE assistant_memory_rebuild_jobs SET status=CASE WHEN ? AND attempts>=5 THEN 'FAILED' ELSE 'RETRY' END,
                  attempts=attempts-CASE WHEN ? THEN 0 ELSE 1 END,last_error=CASE WHEN ? THEN 'EMBEDDING_FAILED' ELSE NULL END,
                  next_attempt_at=CURRENT_TIMESTAMP+(CASE WHEN ? THEN 30 ELSE 1 END * INTERVAL '1 second'),
                  execution_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND execution_token=? AND status='RUNNING'
                """,failed,failed,failed,failed,work.id(),work.token());
    }
    @Transactional
    public void retry(long id,String signature) {
        jdbc.execute("SELECT pg_advisory_xact_lock(7302022)");
        if (jdbc.queryForObject("SELECT count(*) FROM assistant_memory_rebuild_jobs WHERE status IN ('PENDING','RUNNING','RETRY')",Long.class)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有记忆重建任务执行中");
        if (jdbc.update("UPDATE assistant_memory_rebuild_jobs SET status='RETRY',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND signature=? AND status='FAILED'",id,signature)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"任务不是当前配置的失败重建");
    }
    public Map<String,Object> status(long id) {
        var rows=jdbc.queryForList("""
                SELECT j.id,j.signature,j.dimension,j.storage,j.status,j.attempts,j.last_error,
                  (SELECT count(*) FROM assistant_memory_rebuild_items i JOIN assistant_memory_facts f ON f.id=i.fact_id AND f.fact_hash=i.input_hash WHERE i.job_id=j.id) AS staged,
                  (SELECT count(*) FROM assistant_memory_facts) AS total FROM assistant_memory_rebuild_jobs j WHERE j.id=?
                """,id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆重建任务不存在");
        return rows.get(0);
    }
    private boolean owns(Work work) {
        return !jdbc.queryForList("SELECT id FROM assistant_memory_rebuild_jobs WHERE id=? AND execution_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",work.id(),work.token()).isEmpty();
    }
    private void validate(int dimension,String storage) {
        if (dimension<1 || dimension>4000 || (!"vector".equals(storage) && !"halfvec".equals(storage)))
            throw new IllegalArgumentException("Invalid memory vector target");
    }
}
