ALTER TABLE assistant_evaluation_runs
    ADD COLUMN IF NOT EXISTS tool_trace_passed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tool_trace_pass_rate DOUBLE PRECISION NOT NULL DEFAULT 0;

-- Older runs did not capture tool traces, so they intentionally remain zero instead of
-- being inferred from answer quality.
