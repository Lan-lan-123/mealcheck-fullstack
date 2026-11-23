CREATE TABLE auth_token_revocations (
    token_id UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_auth_token_revocations_expiry ON auth_token_revocations(expires_at);
