-- The published source records its splitter; jobs retain the submitted choice across retries/restarts.
ALTER TABLE knowledge_source_sync ADD COLUMN splitter_strategy VARCHAR(40);
ALTER TABLE knowledge_embedding_rebuild_jobs ADD COLUMN splitter_strategy VARCHAR(40);
ALTER TABLE knowledge_source_sync ADD CONSTRAINT knowledge_source_splitter_valid
    CHECK (splitter_strategy IN ('recursive-markdown','langchain4j-recursive','structural-markdown','legacy-window'));
ALTER TABLE knowledge_embedding_rebuild_jobs ADD CONSTRAINT knowledge_job_splitter_valid
    CHECK (splitter_strategy IN ('recursive-markdown','langchain4j-recursive','structural-markdown','legacy-window'));
