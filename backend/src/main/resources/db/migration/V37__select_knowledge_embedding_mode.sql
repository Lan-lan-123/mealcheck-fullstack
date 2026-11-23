CREATE TABLE knowledge_embedding_settings (
    id INTEGER PRIMARY KEY CHECK (id=1),
    mode VARCHAR(16) CHECK (mode IN ('LOCAL','SEMANTIC')),
    signature VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- NULL preserves the deployment's pre-existing embedding configuration.
INSERT INTO knowledge_embedding_settings(id) VALUES (1);
ALTER TABLE knowledge_embedding_rebuild_jobs
    ADD COLUMN embedding_mode VARCHAR(16) CHECK (embedding_mode IN ('LOCAL','SEMANTIC')),
    ADD COLUMN source_synced BOOLEAN NOT NULL DEFAULT TRUE;
