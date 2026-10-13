-- Knowledge schema is managed by Flyway, never by application bean creation.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(255) NOT NULL,
    title TEXT,
    content TEXT NOT NULL,
    embedding halfvec(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE knowledge_chunks
    ADD COLUMN IF NOT EXISTS hit_count BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_hit_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS category VARCHAR(64) DEFAULT 'general',
    ADD COLUMN IF NOT EXISTS embedding_signature VARCHAR(255),
    ADD COLUMN IF NOT EXISTS chunk_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS chunk_index INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS token_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS source_version VARCHAR(64) DEFAULT 'v1',
    ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE knowledge_chunks ALTER COLUMN title TYPE TEXT;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_title_trgm_gist ON knowledge_chunks USING gist (title gist_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_content_trgm_gist ON knowledge_chunks USING gist (content gist_trgm_ops);
CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_chunks_source_key ON knowledge_chunks(source, chunk_key) WHERE chunk_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_content_hash ON knowledge_chunks(content_hash);
DO $$
DECLARE storage_name TEXT;
BEGIN
    SELECT CASE WHEN format_type(atttypid, atttypmod) LIKE 'halfvec%' THEN 'halfvec' ELSE 'vector' END
      INTO storage_name FROM pg_attribute WHERE attrelid='knowledge_chunks'::regclass AND attname='embedding';
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding ON knowledge_chunks USING ivfflat (embedding %s_cosine_ops) WITH (lists = 100)', storage_name);
END $$;

CREATE TABLE knowledge_embedding_rebuild_jobs (
    id BIGSERIAL PRIMARY KEY,
    signature VARCHAR(255) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension BETWEEN 1 AND 2000),
    storage VARCHAR(16) NOT NULL CHECK (storage IN ('vector','halfvec')),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','RETRY','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    execution_token UUID,
    lease_until TIMESTAMP,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- One rebuild at a time, including across application instances.
CREATE UNIQUE INDEX idx_knowledge_rebuild_active ON knowledge_embedding_rebuild_jobs((true))
    WHERE status IN ('PENDING','RUNNING','RETRY');
CREATE TABLE knowledge_embedding_rebuild_items (
    job_id BIGINT NOT NULL REFERENCES knowledge_embedding_rebuild_jobs(id) ON DELETE CASCADE,
    chunk_id BIGINT NOT NULL REFERENCES knowledge_chunks(id) ON DELETE CASCADE,
    input_hash CHAR(32) NOT NULL,
    vector_text TEXT NOT NULL,
    PRIMARY KEY(job_id,chunk_id)
);
