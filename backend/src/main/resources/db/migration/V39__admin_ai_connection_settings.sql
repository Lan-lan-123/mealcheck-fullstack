CREATE TABLE ai_connection_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    model VARCHAR(200),
    base_url VARCHAR(1000),
    api_key_ciphertext TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((model IS NULL AND base_url IS NULL AND api_key_ciphertext IS NULL)
        OR (model IS NOT NULL AND base_url IS NOT NULL AND api_key_ciphertext IS NOT NULL))
);
INSERT INTO ai_connection_settings (id) VALUES (1);
