-- Add D without rewriting existing published source/job selections.
ALTER TABLE knowledge_source_sync DROP CONSTRAINT knowledge_source_splitter_valid;
ALTER TABLE knowledge_source_sync ADD CONSTRAINT knowledge_source_splitter_valid
    CHECK (splitter_strategy IN ('recursive-markdown','langchain4j-recursive','structural-markdown','langchain4j-enhanced','legacy-window'));
ALTER TABLE knowledge_embedding_rebuild_jobs DROP CONSTRAINT knowledge_job_splitter_valid;
ALTER TABLE knowledge_embedding_rebuild_jobs ADD CONSTRAINT knowledge_job_splitter_valid
    CHECK (splitter_strategy IN ('recursive-markdown','langchain4j-recursive','structural-markdown','langchain4j-enhanced','legacy-window'));
