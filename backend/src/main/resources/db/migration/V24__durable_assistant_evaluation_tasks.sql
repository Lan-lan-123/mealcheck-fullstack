CREATE TABLE assistant_evaluation_tasks (
    run_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    definition_json TEXT NOT NULL,
    started_by VARCHAR(64),
    owner_token UUID,
    owner_instance VARCHAR(36),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX idx_assistant_evaluation_one_active
    ON assistant_evaluation_tasks ((1)) WHERE status IN ('PENDING','RUNNING');

-- Legacy runs have no owner or renewable lease. Preserve their partial results;
-- they cannot be safely resumed after upgrading the worker protocol.
UPDATE assistant_evaluation_runs SET status='FAILED', verdict='INCOMPLETE',
    message='评测执行协议已升级，原任务已中断，请显式重新运行',
    finished_at=updated_at
WHERE status='RUNNING';
