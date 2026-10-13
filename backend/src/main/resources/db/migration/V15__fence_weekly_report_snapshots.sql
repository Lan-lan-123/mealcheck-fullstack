-- Legacy snapshots remain readable; new snapshots are unique per durable job.
ALTER TABLE weekly_report_snapshots ADD COLUMN job_id BIGINT;
ALTER TABLE weekly_report_snapshots ADD CONSTRAINT fk_weekly_snapshot_job
    FOREIGN KEY (job_id) REFERENCES weekly_report_jobs(id) ON DELETE CASCADE;
ALTER TABLE weekly_report_snapshots ADD CONSTRAINT uq_weekly_snapshot_job UNIQUE (job_id);
