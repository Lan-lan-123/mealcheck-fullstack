-- Content history survives deletion. Vector rebuilds and hit counters are not content edits.
ALTER TABLE knowledge_chunks ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 1;
CREATE TABLE knowledge_content_versions (
    id BIGSERIAL PRIMARY KEY,
    chunk_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    snapshot JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(chunk_id, revision)
);
CREATE INDEX idx_knowledge_content_versions_recent ON knowledge_content_versions(id DESC);

CREATE FUNCTION knowledge_content_snapshot(k knowledge_chunks) RETURNS JSONB AS $$
    SELECT jsonb_build_object('source', k.source, 'chunk_key', k.chunk_key,
        'title', k.title, 'content', k.content, 'category', k.category,
        'chunk_index', k.chunk_index, 'token_count', k.token_count,
        'content_hash', k.content_hash, 'source_version', k.source_version,
        'source_updated_at', k.source_updated_at);
$$ LANGUAGE SQL;

INSERT INTO knowledge_content_versions(chunk_id, revision, operation, actor, reason, snapshot)
SELECT id, content_revision, 'BASELINE', 'migration', '启用版本记录时的现有正文', knowledge_content_snapshot(k)
FROM knowledge_chunks k;

CREATE FUNCTION track_knowledge_content_version() RETURNS TRIGGER AS $$
DECLARE next_revision BIGINT;
DECLARE entry knowledge_chunks;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF knowledge_content_snapshot(NEW) IS NOT DISTINCT FROM knowledge_content_snapshot(OLD) THEN
            NEW.content_revision := OLD.content_revision;
            RETURN NEW;
        END IF;
        NEW.content_revision := OLD.content_revision + 1;
    ELSIF TG_OP='INSERT' THEN
        SELECT COALESCE(MAX(revision),0)+1 INTO next_revision
        FROM knowledge_content_versions WHERE chunk_id=NEW.id;
        NEW.content_revision := next_revision;
    END IF;
    IF TG_OP='DELETE' THEN
        entry := OLD;
        next_revision := OLD.content_revision+1;
    ELSE
        entry := NEW;
        next_revision := NEW.content_revision;
    END IF;
    INSERT INTO knowledge_content_versions(chunk_id, revision, operation, actor, reason, snapshot)
    VALUES (entry.id, next_revision, TG_OP,
        COALESCE(NULLIF(current_setting('mealcheck.knowledge_actor',true),''),'system'),
        COALESCE(current_setting('mealcheck.knowledge_reason',true),''), knowledge_content_snapshot(entry));
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER knowledge_content_history BEFORE INSERT OR UPDATE OR DELETE ON knowledge_chunks
FOR EACH ROW EXECUTE FUNCTION track_knowledge_content_version();

ALTER TABLE knowledge_embedding_rebuild_jobs ADD COLUMN preview_token VARCHAR(64);
