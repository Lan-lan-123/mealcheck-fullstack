package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Global admission and publication fencing; never holds a transaction during model or SSE I/O. */
@Service
public class AssistantTaskStore {
    public record Lease(String requestId, long userId, String instanceId, String token) { }
    public record Heartbeat(String requestId, String token, String state) { }
    public record Status(String requestId, String state) { }
    public enum Cancellation { REQUESTED, COMPLETED, ABSENT }
    private final JdbcTemplate jdbc;
    public AssistantTaskStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public Lease admit(String requestId,long userId,String instanceId,long timeoutMillis,int leaseSeconds) {
        boundIdleTransaction();
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",userId).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        jdbc.update("""
            UPDATE assistant_tasks SET state=CASE WHEN published_at IS NOT NULL THEN 'SUCCEEDED'
              WHEN deadline_at<=clock_timestamp() THEN 'TIMED_OUT' ELSE 'INTERRUPTED' END,
              updated_at=clock_timestamp() WHERE user_id=? AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')
              AND lease_until<=clock_timestamp()
            """,userId);
        if(jdbc.queryForObject("SELECT count(*) FROM assistant_tasks WHERE user_id=? AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')",Long.class,userId)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"上一次回复仍在生成或清理，请稍后再试。");
        var lease=new Lease(requestId,userId,instanceId,UUID.randomUUID().toString());
        jdbc.update("""
            INSERT INTO assistant_tasks(request_id,user_id,instance_id,execution_token,state,deadline_at,lease_until)
            VALUES (?,?,?,?,'QUEUED',clock_timestamp()+(? * INTERVAL '1 millisecond'),clock_timestamp()+(? * INTERVAL '1 second'))
            """,requestId,userId,instanceId,lease.token(),Math.max(1,timeoutMillis),leaseSeconds);
        return lease;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public boolean start(Lease lease) {
        boundIdleTransaction();
        return jdbc.update("""
            UPDATE assistant_tasks SET state='RUNNING',updated_at=clock_timestamp()
            WHERE request_id=? AND instance_id=? AND execution_token=? AND state='QUEUED'
              AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """,lease.requestId(),lease.instanceId(),lease.token())==1;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public List<Heartbeat> heartbeat(String instanceId,List<Lease> owned,int leaseSeconds) {
        if(owned.isEmpty())return List.of();
        boundIdleTransaction();
        String placeholders=String.join(",",java.util.Collections.nCopies(owned.size(),"(?,?)"));
        var args=new java.util.ArrayList<Object>();args.add(leaseSeconds);args.add(instanceId);
        for(var lease:owned){args.add(lease.requestId());args.add(lease.token());}
        return jdbc.query("""
            UPDATE assistant_tasks SET lease_until=clock_timestamp()+(? * INTERVAL '1 second')
            WHERE instance_id=? AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND lease_until>clock_timestamp()
            AND (request_id,execution_token) IN (
            """+placeholders+") "+"""
            RETURNING request_id,execution_token,state
            """,(rs,n)->new Heartbeat(rs.getString(1),rs.getString(2),rs.getString(3)),args.toArray());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public Cancellation cancel(long userId,String requestId) {
        boundIdleTransaction();
        int changed=jdbc.update("""
            UPDATE assistant_tasks SET state='CANCEL_REQUESTED',updated_at=clock_timestamp()
            WHERE user_id=? AND request_id=? AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')
              AND published_at IS NULL AND lease_until>clock_timestamp()
            """,userId,requestId);
        if(changed==1)return Cancellation.REQUESTED;
        var published=jdbc.queryForList("SELECT request_id FROM assistant_tasks WHERE user_id=? AND request_id=? AND published_at IS NOT NULL",userId,requestId);
        return published.isEmpty()?Cancellation.ABSENT:Cancellation.COMPLETED;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void finish(Lease lease,String outcome) {
        if(!java.util.Set.of("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT","INTERRUPTED").contains(outcome))
            throw new IllegalArgumentException("Assistant cleanup requires a terminal outcome");
        boundIdleTransaction();
        jdbc.update("""
            UPDATE assistant_tasks SET state=CASE WHEN published_at IS NOT NULL THEN 'SUCCEEDED'
              WHEN state='CANCEL_REQUESTED' AND ?='SUCCEEDED' THEN 'CANCELLED' ELSE ? END,updated_at=clock_timestamp()
            WHERE request_id=? AND instance_id=? AND execution_token=?
              AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND lease_until>clock_timestamp()
            """,outcome,outcome,lease.requestId(),lease.instanceId(),lease.token());
    }

    /** Called after the user row is locked, in the same transaction that appends the conversation turn. */
    @Transactional(propagation=Propagation.MANDATORY, timeout=3)
    public void guardPublication(Lease lease,boolean complete) {
        boundIdleTransaction();
        var rows=jdbc.queryForList("""
            SELECT state,published_at,lease_until>clock_timestamp() AS live,deadline_at>clock_timestamp() AS timely
            FROM assistant_tasks WHERE request_id=? AND user_id=? AND instance_id=? AND execution_token=? FOR UPDATE
            """,lease.requestId(),lease.userId(),lease.instanceId(),lease.token());
        if(rows.isEmpty())throw new CancellationException("助手执行已失去所有权");
        var row=rows.get(0);String state=row.get("state").toString();
        if(!Boolean.TRUE.equals(row.get("live")) || row.get("published_at")!=null
                || !(complete?state.equals("RUNNING") && Boolean.TRUE.equals(row.get("timely"))
                    :state.equals("RUNNING") || state.equals("CANCEL_REQUESTED")))
            throw new CancellationException("助手执行已取消、过期或完成");
        if(complete)jdbc.update("UPDATE assistant_tasks SET published_at=clock_timestamp() WHERE request_id=?",lease.requestId());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void preparePublication() {
        // Configure the server before acquiring even the first publication lock.
        boundIdleTransaction();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=3)
    public void reap() {
        boundIdleTransaction();
        jdbc.update("""
            UPDATE assistant_tasks SET state=CASE WHEN published_at IS NOT NULL THEN 'SUCCEEDED'
              WHEN deadline_at<=clock_timestamp() THEN 'TIMED_OUT' ELSE 'INTERRUPTED' END,updated_at=clock_timestamp()
            WHERE request_id IN (SELECT request_id FROM assistant_tasks
              WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND lease_until<=clock_timestamp()
              LIMIT 100 FOR UPDATE SKIP LOCKED)
            """);
        jdbc.update("""
            DELETE FROM assistant_tasks WHERE request_id IN (SELECT request_id FROM assistant_tasks
              WHERE state NOT IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND updated_at<clock_timestamp()-INTERVAL '30 days'
              LIMIT 100 FOR UPDATE SKIP LOCKED)
            """);
    }

    @Transactional(readOnly=true, timeout=3)
    public Status status(long userId,String requestId) {
        var rows=jdbc.queryForList("""
            SELECT CASE WHEN state IN ('QUEUED','RUNNING','CANCEL_REQUESTED') AND lease_until<=clock_timestamp()
              THEN CASE WHEN published_at IS NOT NULL THEN 'SUCCEEDED' WHEN deadline_at<=clock_timestamp() THEN 'TIMED_OUT' ELSE 'INTERRUPTED' END
              ELSE state END AS state FROM assistant_tasks WHERE user_id=? AND request_id=?
            """,userId,requestId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"助手请求不存在");
        return new Status(requestId,rows.get(0).get("state").toString());
    }

    private void boundIdleTransaction() {
        // A frozen JVM cannot run client-side timeouts. Let PostgreSQL release its locks independently.
        jdbc.execute("SET LOCAL idle_in_transaction_session_timeout = '3s'");
    }
}
