-- Preserve the canonical string identity, but join/lock the weekly job by its bigint PK.
-- Invalid, non-canonical or out-of-range legacy keys never authorize a weekly retry.
ALTER TABLE task_failure_events ADD COLUMN weekly_report_job_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN task_type='WEEKLY_REPORT'
        AND task_key ~ '^[1-9][0-9]{0,18}$'
        AND (length(task_key)<19 OR task_key COLLATE "C" <= '9223372036854775807' COLLATE "C")
    THEN task_key::bigint ELSE NULL END
) STORED;
