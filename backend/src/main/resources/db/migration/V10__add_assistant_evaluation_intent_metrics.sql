ALTER TABLE assistant_evaluation_runs
    ADD COLUMN IF NOT EXISTS intent_evaluated_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS intent_matched_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS intent_accuracy DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS intent_macro_f1 DOUBLE PRECISION NOT NULL DEFAULT 0;

-- Historical runs did not contain intent labels and intentionally remain zero.
