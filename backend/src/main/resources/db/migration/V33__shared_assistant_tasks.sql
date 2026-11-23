CREATE TABLE assistant_tasks (
    request_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    instance_id VARCHAR(80) NOT NULL,
    execution_token VARCHAR(36) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED')),
    deadline_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX idx_assistant_tasks_active_user ON assistant_tasks(user_id)
    WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED');
CREATE INDEX idx_assistant_tasks_active_lease ON assistant_tasks(lease_until)
    WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED');
CREATE INDEX idx_assistant_tasks_owner ON assistant_tasks(instance_id)
    WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED');
CREATE INDEX idx_assistant_tasks_retention ON assistant_tasks(updated_at)
    WHERE state NOT IN ('QUEUED','RUNNING','CANCEL_REQUESTED');
