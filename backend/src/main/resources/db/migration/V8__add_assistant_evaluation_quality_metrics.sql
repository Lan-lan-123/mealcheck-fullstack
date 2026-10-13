ALTER TABLE assistant_evaluation_runs
    ADD COLUMN IF NOT EXISTS verdict VARCHAR(20) NOT NULL DEFAULT 'NOT_RUN',
    ADD COLUMN IF NOT EXISTS judged_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS execution_success_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS end_to_end_pass_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS macro_category_pass_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS end_to_end_average_score DOUBLE PRECISION NOT NULL DEFAULT 0;

-- Best-effort backfill for runs created before the quality/error metrics were separated.
UPDATE assistant_evaluation_runs
SET judged_count = GREATEST(completed_count - error_count, 0),
    failed_count = GREATEST(completed_count - passed_count - error_count, 0),
    execution_success_rate = CASE
        WHEN case_count > 0 THEN GREATEST(completed_count - error_count, 0)::DOUBLE PRECISION / case_count
        ELSE 0
    END,
    end_to_end_pass_rate = CASE
        WHEN case_count > 0 THEN passed_count::DOUBLE PRECISION / case_count
        ELSE 0
    END,
    end_to_end_average_score = average_score,
    average_score = CASE
        WHEN completed_count - error_count > 0
            THEN average_score * completed_count / (completed_count - error_count)
        ELSE 0
    END,
    pass_rate = CASE
        WHEN completed_count - error_count > 0
            THEN passed_count::DOUBLE PRECISION / (completed_count - error_count)
        ELSE 0
    END,
    verdict = CASE
        WHEN hard_gate_failure_count > 0 THEN 'BLOCKED'
        WHEN status <> 'COMPLETED' OR error_count > 0 THEN 'INCOMPLETE'
        WHEN passed_count::DOUBLE PRECISION / NULLIF(completed_count, 0) >= 0.9 THEN 'PASSED'
        ELSE 'NEEDS_REVIEW'
    END;
