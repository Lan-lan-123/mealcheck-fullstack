ALTER TABLE rag_search_events
    ADD COLUMN IF NOT EXISTS rough_top_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reranker_top_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS final_top_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reranker_applied BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS no_answer BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS threshold_filtered_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS duplicate_filtered_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_rag_search_events_no_answer_created_at
    ON rag_search_events(no_answer, created_at DESC);
