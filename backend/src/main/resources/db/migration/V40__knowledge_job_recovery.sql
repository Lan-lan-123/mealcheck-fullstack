ALTER TABLE knowledge_embedding_rebuild_jobs DROP CONSTRAINT knowledge_embedding_rebuild_jobs_status_check;
ALTER TABLE knowledge_embedding_rebuild_jobs
    ADD CONSTRAINT knowledge_embedding_rebuild_jobs_status_check
        CHECK (status IN ('PENDING','RUNNING','RETRY','SUCCEEDED','FAILED','CANCELLED','INVALIDATED')),
    ADD COLUMN failure_summary VARCHAR(240),
    ADD COLUMN failure_retryable BOOLEAN,
    ADD COLUMN finished_at TIMESTAMPTZ;

CREATE TABLE knowledge_embedding_job_events (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES knowledge_embedding_rebuild_jobs(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    summary VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_knowledge_job_events ON knowledge_embedding_job_events(job_id,id DESC);
