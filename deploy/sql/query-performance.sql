-- Run with an authorized monitoring/DBA role. Do not expose this through a public API.
-- Query identifiers avoid dumping SQL literals, user messages or credentials into reports.
SELECT queryid,calls,round(total_exec_time::numeric,2) AS total_ms,
       round(mean_exec_time::numeric,2) AS mean_ms,rows,
       shared_blks_hit,shared_blks_read,temp_blks_written
FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
ORDER BY total_exec_time DESC LIMIT 20;

SELECT wait_event_type,wait_event,count(*) AS connections
FROM pg_stat_activity WHERE datname=current_database() AND state='active'
GROUP BY wait_event_type,wait_event ORDER BY connections DESC;

SELECT pid,pg_blocking_pids(pid) AS blockers,now()-xact_start AS transaction_age,
       wait_event_type,wait_event
FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0
ORDER BY xact_start;

SELECT relname,indexrelname,idx_scan,pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes ORDER BY idx_scan,pg_relation_size(indexrelid) DESC LIMIT 30;

SELECT relname,n_live_tup,n_dead_tup,last_autovacuum,last_autoanalyze
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 20;
