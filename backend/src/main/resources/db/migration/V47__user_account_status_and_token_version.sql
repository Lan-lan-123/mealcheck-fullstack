ALTER TABLE users
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0
        CHECK (token_version >= 0);
