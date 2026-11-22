-- Namespace search caches with a revision shared by every application instance.
-- Knowledge edits and revision changes commit or roll back together. Hit counters
-- are deliberately excluded so normal searches do not invalidate the cache.
CREATE TABLE knowledge_cache_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO knowledge_cache_state(id,revision) VALUES (1,0);

CREATE FUNCTION advance_knowledge_cache_revision() RETURNS trigger AS $$
BEGIN
    -- Keep column references inside PL/pgSQL, not UPDATE OF or a trigger WHEN.
    -- Explicit trigger-column dependencies prevent the rebuild worker from
    -- changing embedding between vector/halfvec and different dimensions.
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
    UPDATE knowledge_cache_state SET revision=revision+1 WHERE id=1;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER knowledge_cache_insert_delete
    AFTER INSERT OR DELETE OR TRUNCATE ON knowledge_chunks
    FOR EACH STATEMENT EXECUTE FUNCTION advance_knowledge_cache_revision();
CREATE TRIGGER knowledge_cache_content_update
    AFTER UPDATE ON knowledge_chunks
    FOR EACH ROW EXECUTE FUNCTION advance_knowledge_cache_revision();
