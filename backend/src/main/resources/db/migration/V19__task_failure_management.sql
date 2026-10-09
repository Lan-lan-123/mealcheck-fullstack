ALTER TABLE weekly_report_jobs ADD COLUMN execution_token VARCHAR(36) NOT NULL DEFAULT gen_random_uuid()::text;
ALTER TABLE weekly_report_jobs ADD COLUMN manual_retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE meal_analysis_requests ADD COLUMN manual_retry_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE task_failure_events (
    id BIGSERIAL PRIMARY KEY,
    dedupe_key VARCHAR(200) NOT NULL UNIQUE,
    task_type VARCHAR(24) NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    task_key VARCHAR(80),
    event_id UUID,
    execution_token VARCHAR(36),
    attempts INTEGER NOT NULL DEFAULT 0,
    phase VARCHAR(24) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    error_summary VARCHAR(300) NOT NULL,
    recoverable BOOLEAN NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    dead_lettered BOOLEAN NOT NULL DEFAULT false,
    handling_note VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    failed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_task_failure_status_time ON task_failure_events(status, failed_at DESC, id DESC);
CREATE INDEX idx_task_failure_subject ON task_failure_events(task_type, user_id, task_key, id DESC);
CREATE INDEX idx_task_failure_event ON task_failure_events(event_id);

CREATE TABLE task_failure_operations (
    operation_id UUID PRIMARY KEY,
    failure_id BIGINT NOT NULL REFERENCES task_failure_events(id) ON DELETE CASCADE,
    action VARCHAR(16) NOT NULL,
    admin_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expected_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Existing terminal failures must be visible immediately after upgrading.
INSERT INTO task_failure_events(dedupe_key,task_type,user_id,task_key,event_id,execution_token,attempts,phase,error_code,error_summary,recoverable,failed_at)
SELECT 'MEAL_ANALYSIS:'||r.user_id||':'||r.request_key||':'||r.attempt_token,
       'MEAL_ANALYSIS',r.user_id,r.request_key,o.event_id,r.attempt_token,r.attempts,'UNKNOWN',
       CASE WHEN r.last_error='WORKER_INTERRUPTED' THEN 'WORKER_INTERRUPTED' ELSE 'LEGACY_FAILURE' END,
       '升级前的分析失败；历史详细原因未记录',true,r.updated_at
FROM meal_analysis_requests r LEFT JOIN meal_analysis_outbox o USING(user_id,request_key)
WHERE r.status='FAILED';
INSERT INTO task_failure_events(dedupe_key,task_type,user_id,task_key,execution_token,attempts,phase,error_code,error_summary,recoverable,failed_at)
SELECT 'WEEKLY_REPORT:'||user_id||':'||id||':'||execution_token,
       'WEEKLY_REPORT',user_id,id::text,execution_token,attempts,'UNKNOWN','LEGACY_FAILURE',
       '升级前的周报失败；请结合协调记录排查',true,updated_at
FROM weekly_report_jobs WHERE status='DEAD';
