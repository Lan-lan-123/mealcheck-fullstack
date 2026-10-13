-- Keep legacy text and vectors; future writes no longer depend on embedding availability.
CREATE TABLE IF NOT EXISTS assistant_memory_facts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id BIGINT REFERENCES assistant_conversations(id) ON DELETE CASCADE,
    fact_type VARCHAR(32) NOT NULL,
    fact_text TEXT NOT NULL,
    fact_hash CHAR(64) NOT NULL,
    embedding vector,
    embedding_signature VARCHAR(255),
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE assistant_memory_facts ALTER COLUMN embedding DROP NOT NULL;
ALTER TABLE assistant_memory_facts ALTER COLUMN embedding_signature DROP NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_assistant_memory_user_hash ON assistant_memory_facts(user_id,fact_hash);
CREATE INDEX IF NOT EXISTS idx_assistant_memory_user_seen ON assistant_memory_facts(user_id,last_seen_at DESC);

CREATE TABLE assistant_memory_vectors (
    fact_id BIGINT NOT NULL REFERENCES assistant_memory_facts(id) ON DELETE CASCADE,
    signature VARCHAR(320) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension BETWEEN 1 AND 4000),
    embedding vector,
    embedding_half halfvec,
    PRIMARY KEY (fact_id,signature),
    CHECK ((embedding IS NULL) <> (embedding_half IS NULL))
);
-- Existing half precision values are retained at their original precision.
INSERT INTO assistant_memory_vectors(fact_id,signature,input_hash,dimension,embedding,embedding_half)
SELECT id,embedding_signature || CASE WHEN pg_typeof(embedding)::text='halfvec' THEN ':halfvec-v1' ELSE ':vector-v1' END,
    fact_hash,vector_dims(embedding::vector),
    CASE WHEN pg_typeof(embedding)::text='halfvec' THEN NULL ELSE embedding::vector END,
    CASE WHEN pg_typeof(embedding)::text='halfvec' THEN embedding::halfvec ELSE NULL END
FROM assistant_memory_facts WHERE embedding IS NOT NULL AND embedding_signature IS NOT NULL;

CREATE TABLE assistant_memory_version (
    id INTEGER PRIMARY KEY CHECK (id=1),
    signature VARCHAR(320),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO assistant_memory_version(id,signature)
VALUES (1,(SELECT signature FROM assistant_memory_vectors ORDER BY fact_id DESC LIMIT 1));

CREATE TABLE assistant_memory_rebuild_jobs (
    id BIGSERIAL PRIMARY KEY,
    signature VARCHAR(320) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension BETWEEN 1 AND 4000),
    storage VARCHAR(8) NOT NULL CHECK (storage IN ('vector','halfvec')),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','RETRY','FAILED','SUCCEEDED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    execution_token UUID,
    lease_until TIMESTAMP,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_memory_one_active_rebuild ON assistant_memory_rebuild_jobs((true)) WHERE status IN ('PENDING','RUNNING','RETRY');
CREATE TABLE assistant_memory_rebuild_items (
    job_id BIGINT NOT NULL REFERENCES assistant_memory_rebuild_jobs(id) ON DELETE CASCADE,
    fact_id BIGINT NOT NULL REFERENCES assistant_memory_facts(id) ON DELETE CASCADE,
    input_hash CHAR(64) NOT NULL,
    vector_text TEXT NOT NULL,
    PRIMARY KEY(job_id,fact_id)
);
