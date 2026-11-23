CREATE TABLE knowledge_embedding_profiles (
    id BIGSERIAL PRIMARY KEY,
    model VARCHAR(200) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    api_key_ciphertext TEXT NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension BETWEEN 1 AND 2000),
    send_dimensions BOOLEAN NOT NULL DEFAULT TRUE,
    source VARCHAR(16) NOT NULL CHECK (source IN ('ADMIN','ENVIRONMENT')),
    tested_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE knowledge_embedding_settings
    ADD COLUMN active_profile_id BIGINT REFERENCES knowledge_embedding_profiles(id),
    ADD COLUMN draft_profile_id BIGINT REFERENCES knowledge_embedding_profiles(id),
    ADD COLUMN configuration_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_embedding_rebuild_jobs
    ADD COLUMN profile_id BIGINT REFERENCES knowledge_embedding_profiles(id);
