-- Usage belongs to the vector returned by the same provider call. NULL means unreported,
-- historical, or locally generated; never backfill actual usage from token_count estimates.
ALTER TABLE knowledge_chunks
    ADD COLUMN embedding_input_tokens BIGINT CHECK (embedding_input_tokens >= 0),
    ADD COLUMN embedding_total_tokens BIGINT CHECK (embedding_total_tokens >= 0);
ALTER TABLE knowledge_embedding_rebuild_items
    ADD COLUMN embedding_input_tokens BIGINT CHECK (embedding_input_tokens >= 0),
    ADD COLUMN embedding_total_tokens BIGINT CHECK (embedding_total_tokens >= 0);
