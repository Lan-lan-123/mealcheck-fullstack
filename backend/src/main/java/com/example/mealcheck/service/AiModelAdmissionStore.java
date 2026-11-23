package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

/** Separate from the global gate: a busy model must not consume another model's permits. */
@Service
public class AiModelAdmissionStore {
    private final JdbcTemplate jdbc;
    public AiModelAdmissionStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Admission(UUID id,String reason,long retryMillis) { }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
    public Admission acquire(String scope,int concurrency,int rpm,long tpm,long tokens,long leaseMillis) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 742019052))",(rs,n)->0,scope);
        jdbc.update("INSERT INTO ai_model_admission_state(scope) VALUES (?) ON CONFLICT DO NOTHING",scope);
        long cooldown=jdbc.queryForObject("SELECT greatest(0,ceil(extract(epoch FROM (cooldown_until-clock_timestamp()))*1000))::bigint FROM ai_model_admission_state WHERE scope=?",Long.class,scope);
        if(cooldown>0)return new Admission(null,"cooldown",cooldown);
        jdbc.update("DELETE FROM ai_model_admission_starts WHERE scope=? AND started_at<clock_timestamp()-INTERVAL '60 seconds' AND (released OR expires_at<=clock_timestamp())",scope);
        long active=jdbc.queryForObject("SELECT count(*) FROM ai_model_admission_starts WHERE scope=? AND NOT released AND expires_at>clock_timestamp()",Long.class,scope);
        if(active>=concurrency)return new Admission(null,"concurrency",100);
        var window=jdbc.queryForMap("""
            SELECT count(*) AS starts,coalesce(sum(tokens),0) AS tokens,
              greatest(0,coalesce(ceil(extract(epoch FROM (max(started_at)+(? * INTERVAL '1 millisecond')-clock_timestamp()))*1000),0))::bigint AS pace
            FROM ai_model_admission_starts WHERE scope=? AND started_at>clock_timestamp()-INTERVAL '60 seconds'
            """,(long)Math.ceil(60000.0/rpm),scope);
        if(((Number)window.get("starts")).longValue()>=rpm)return new Admission(null,"rpm",1000);
        if(tpm>0 && ((Number)window.get("tokens")).longValue()+tokens>tpm)return new Admission(null,"tpm",1000);
        long pace=((Number)window.get("pace")).longValue();
        if(pace>0)return new Admission(null,"pacing",pace);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO ai_model_admission_starts(id,scope,expires_at,tokens) VALUES (?,?,clock_timestamp()+(? * INTERVAL '1 millisecond'),?)",id,scope,leaseMillis,tokens);
        return new Admission(id,"accepted",0);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
    public void cooldown(String scope,long millis) {
        jdbc.update("""
            INSERT INTO ai_model_admission_state(scope,cooldown_until) VALUES (?,clock_timestamp()+(? * INTERVAL '1 millisecond'))
            ON CONFLICT(scope) DO UPDATE SET cooldown_until=greatest(ai_model_admission_state.cooldown_until,excluded.cooldown_until)
            """,scope,millis);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=3)
    public void release(UUID id,Long actualTokens) {
        jdbc.update("UPDATE ai_model_admission_starts SET released=true,tokens=coalesce(?,tokens) WHERE id=?",actualTokens,id);
    }
}
