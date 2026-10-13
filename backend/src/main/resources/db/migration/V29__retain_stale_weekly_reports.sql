ALTER TABLE weekly_report_snapshots ADD COLUMN stale BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE weekly_report_snapshots ADD COLUMN invalidated_at TIMESTAMP;
CREATE INDEX idx_weekly_snapshots_active_user_days
    ON weekly_report_snapshots(user_id,days,generated_at DESC,id DESC) WHERE stale=false;
