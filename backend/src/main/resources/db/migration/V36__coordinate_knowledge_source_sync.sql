CREATE TABLE knowledge_source_sync (
    source VARCHAR(255) PRIMARY KEY,
    data_revision BIGINT NOT NULL DEFAULT 0,
    completed_fingerprint VARCHAR(64),
    target_fingerprint VARCHAR(64),
    embedding_signature VARCHAR(255),
    instance_id VARCHAR(36),
    execution_token UUID,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Track edits per source, including writes from other instances and rebuild activation.
-- Hit statistics do not change the source revision.
CREATE FUNCTION track_knowledge_source_revision() RETURNS trigger AS $$
BEGIN
    IF TG_OP='TRUNCATE' THEN
        UPDATE knowledge_source_sync SET data_revision=data_revision+1;
        RETURN NULL;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.source,NEW.title,NEW.content,NEW.category,NEW.embedding_signature,
               NEW.content_hash,NEW.chunk_key,NEW.chunk_index,NEW.token_count,
               NEW.source_version,NEW.source_updated_at)
           IS NOT DISTINCT FROM
           ROW(OLD.source,OLD.title,OLD.content,OLD.category,OLD.embedding_signature,
               OLD.content_hash,OLD.chunk_key,OLD.chunk_index,OLD.token_count,
               OLD.source_version,OLD.source_updated_at)
           AND NEW.embedding::text IS NOT DISTINCT FROM OLD.embedding::text THEN
            RETURN NULL;
        END IF;
    END IF;
    IF TG_OP='DELETE' OR (TG_OP='UPDATE' AND OLD.source IS DISTINCT FROM NEW.source) THEN
        INSERT INTO knowledge_source_sync(source,data_revision) VALUES (OLD.source,1)
        ON CONFLICT(source) DO UPDATE SET data_revision=knowledge_source_sync.data_revision+1;
    END IF;
    IF TG_OP<>'DELETE' THEN
        INSERT INTO knowledge_source_sync(source,data_revision) VALUES (NEW.source,1)
        ON CONFLICT(source) DO UPDATE SET data_revision=knowledge_source_sync.data_revision+1;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER knowledge_source_content_revision
    AFTER INSERT OR UPDATE OR DELETE ON knowledge_chunks
    FOR EACH ROW EXECUTE FUNCTION track_knowledge_source_revision();
CREATE TRIGGER knowledge_source_truncate_revision
    AFTER TRUNCATE ON knowledge_chunks
    FOR EACH STATEMENT EXECUTE FUNCTION track_knowledge_source_revision();
