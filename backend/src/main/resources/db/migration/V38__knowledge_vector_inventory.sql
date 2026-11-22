ALTER TABLE knowledge_embedding_rebuild_jobs
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'ALL' CHECK (scope IN ('ALL','SELECTED')),
    ADD COLUMN completed_chunks BIGINT;

CREATE TABLE knowledge_embedding_rebuild_targets (
    job_id BIGINT NOT NULL REFERENCES knowledge_embedding_rebuild_jobs(id) ON DELETE CASCADE,
    chunk_id BIGINT NOT NULL REFERENCES knowledge_chunks(id) ON DELETE CASCADE,
    PRIMARY KEY(job_id, chunk_id)
);

-- Historical rows keep NULL: a content update timestamp is not proof of vector generation time.
ALTER TABLE knowledge_chunks ADD COLUMN embedding_updated_at TIMESTAMPTZ;
CREATE FUNCTION knowledge_vector_timestamp() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.embedding_updated_at := CURRENT_TIMESTAMP;
    -- Extract through the record so a vector -> halfvec column swap cannot reuse
    -- a cached PL/pgSQL parameter with the former embedding type.
    ELSIF (to_jsonb(NEW)->>'embedding') IS DISTINCT FROM (to_jsonb(OLD)->>'embedding')
       OR NEW.embedding_signature IS DISTINCT FROM OLD.embedding_signature THEN
        NEW.embedding_updated_at := CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_knowledge_vector_timestamp
BEFORE INSERT OR UPDATE ON knowledge_chunks
FOR EACH ROW EXECUTE FUNCTION knowledge_vector_timestamp();
