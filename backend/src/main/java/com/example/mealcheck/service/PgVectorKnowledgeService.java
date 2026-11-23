package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.AdminAssistantStatsResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkPageResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PgVectorKnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeService.class);
    private static final Pattern VECTOR_TYPE = Pattern.compile("(vector|halfvec)\\((\\d+)\\)");
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeEmbeddingService embeddingService;
    private final AppProperties properties;
    private final KnowledgeVectorPersistenceService vectorPersistenceService;
    private final MarkdownKnowledgeChunker chunker;
    private final VectorCompressionService compressionService;
    private final KnowledgeSourceSyncStore sourceSync;

    public PgVectorKnowledgeService(JdbcTemplate jdbcTemplate,
                                    KnowledgeEmbeddingService embeddingService,
                                    AppProperties properties,
                                    KnowledgeVectorPersistenceService vectorPersistenceService,
                                    MarkdownKnowledgeChunker chunker,
                                    VectorCompressionService compressionService,
                                    KnowledgeSourceSyncStore sourceSync) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.vectorPersistenceService = vectorPersistenceService;
        this.chunker = chunker;
        this.compressionService = compressionService;
        this.sourceSync = sourceSync;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_chunks", Long.class);
        return count == null ? 0 : count;
    }

    public long searchRevision() {
        return jdbcTemplate.queryForObject("SELECT revision FROM knowledge_cache_state WHERE id=1", Long.class);
    }

    public SyncResult syncSource(String source,
                                 String sourceVersion,
                                 LocalDateTime sourceUpdatedAt,
                                 List<KnowledgeChunk> chunks) {
        return syncSource(source,sourceVersion,sourceUpdatedAt,chunks,true);
    }

    public SyncResult syncSource(String source,String sourceVersion,LocalDateTime sourceUpdatedAt,
                                 List<KnowledgeChunk> chunks,boolean explicit) {
        return syncSource(source, sourceVersion, sourceUpdatedAt, chunks, explicit, null);
    }

    public String splitterStrategy(String source) { return sourceSync.splitterStrategy(source); }

    public SyncResult syncSource(String source,String sourceVersion,LocalDateTime sourceUpdatedAt,
                                 List<KnowledgeChunk> chunks,boolean explicit,String splitterStrategy) {
        return syncSource(source,sourceVersion,sourceUpdatedAt,chunks,explicit,splitterStrategy,null,null);
    }
    public SyncResult syncSource(String source,String sourceVersion,LocalDateTime sourceUpdatedAt,
                                 List<KnowledgeChunk> chunks,boolean explicit,String splitterStrategy,Long expectedRevision,KnowledgeRebuildStore.Work work) {
        requireCompatibleStorage();
        String signature=activeSignature();
        var admission=expectedRevision==null&&work==null
                ?sourceSync.claim(source,sourceFingerprint(safeVersion(sourceVersion),signature,chunks),signature,explicit)
                :sourceSync.claim(source,sourceFingerprint(safeVersion(sourceVersion),signature,chunks),signature,explicit,expectedRevision,work);
        if(admission.current())return new SyncResult(admission.currentCount(),0,0,admission.currentCount(),0);
        var lease=admission.lease();
        try {
            return prepareAndPublishSource(source,sourceVersion,sourceUpdatedAt,chunks,lease,explicit,splitterStrategy);
        } catch(RuntimeException failure) {
            try {sourceSync.release(lease,failure instanceof KnowledgeSourceSyncStore.Conflict conflict ? conflict.reason().name() : "SYNC_FAILED");}
            catch(RuntimeException releaseFailure) {log.warn("Knowledge source lease cleanup deferred source={}",source);}
            throw failure;
        }
    }

    private SyncResult prepareAndPublishSource(String source,String sourceVersion,LocalDateTime sourceUpdatedAt,
                                               List<KnowledgeChunk> chunks,KnowledgeSourceSyncStore.Lease lease,boolean explicit,String splitterStrategy) {
        List<ExistingChunk> stored = jdbcTemplate.query("""
                SELECT id, chunk_key, content_hash, embedding_signature,title,content
                FROM knowledge_chunks
                WHERE source = ?
                ORDER BY id
                """, (rs, rowNum) -> new ExistingChunk(
                rs.getLong("id"), rs.getString("chunk_key"), rs.getString("content_hash"),
                rs.getString("embedding_signature"),rs.getString("title"),rs.getString("content")), source);
        Map<String, ExistingChunk> byKey = new HashMap<>();
        Set<Long> staleIds = new HashSet<>();
        for (ExistingChunk existing : stored) {
            staleIds.add(existing.id());
            if (existing.chunkKey() != null && !existing.chunkKey().isBlank()) {
                byKey.put(existing.chunkKey(), existing);
            }
        }
        // Adopt an existing matching index on upgrade, but require an explicit
        // administrator action before replacing edits or a different source bundle.
        if(!explicit && !stored.isEmpty() && (stored.size()!=chunks.size() || chunks.stream().anyMatch(chunk->{
            var existing=byKey.get(chunk.chunkKey());
            return existing==null || !Objects.equals(existing.title(),chunk.title()) || !Objects.equals(existing.content(),chunk.content());
        })))throw new KnowledgeSourceSyncStore.Conflict(KnowledgeSourceSyncStore.Reason.REVIEW_REQUIRED);

        String signature = lease.signature();
        List<KnowledgeVectorPersistenceService.KnowledgeSyncRow> prepared = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (KnowledgeChunk chunk : chunks) {
            sourceSync.renew(lease);
            ExistingChunk existing = byKey.get(chunk.chunkKey());
            if (existing != null) {
                staleIds.remove(existing.id());
            }
            boolean needsVector = existing == null
                    || !Objects.equals(existing.contentHash(), chunk.contentHash())
                    || !Objects.equals(existing.embeddingSignature(), signature);
            var embedding = needsVector
                    ? embeddingService.embedForSignatureWithUsage(embeddingText(chunk.title(), chunk.content()), signature) : null;
            String vector = embedding == null ? null : embeddingService.toPgVector(embedding.vector());
            prepared.add(new KnowledgeVectorPersistenceService.KnowledgeSyncRow(
                    existing == null ? null : existing.id(), chunk.chunkKey(), chunk.chunkIndex(), chunk.title(),
                    chunk.content(), inferCategory(chunk.title(), chunk.content()), vector, signature,
                    chunk.contentHash(), chunk.tokenCount(), safeVersion(sourceVersion), sourceUpdatedAt,
                    embedding == null ? null : embedding.inputTokens(), embedding == null ? null : embedding.totalTokens()));
            if (existing == null) {
                inserted++;
            } else if (needsVector) {
                updated++;
            } else {
                unchanged++;
            }
        }

        if (splitterStrategy == null) sourceSync.publish(lease, prepared, new ArrayList<>(staleIds));
        else sourceSync.publish(lease, prepared, new ArrayList<>(staleIds), splitterStrategy);
        return new SyncResult(chunks.size(), inserted, updated, unchanged, staleIds.size());
    }

    static String sourceFingerprint(String version,String signature,List<KnowledgeChunk> chunks) {
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            fingerprintField(hash,"source-sync-v1");fingerprintField(hash,version);fingerprintField(hash,signature);
            for(var chunk:chunks) {
                fingerprintField(hash,chunk.chunkKey());fingerprintField(hash,chunk.title());fingerprintField(hash,chunk.content());
                fingerprintField(hash,Integer.toString(chunk.chunkIndex()));fingerprintField(hash,Integer.toString(chunk.tokenCount()));
                fingerprintField(hash,chunk.contentHash());
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static void fingerprintField(java.security.MessageDigest hash,String value) {
        if(value==null){hash.update(java.nio.ByteBuffer.allocate(4).putInt(-1).array());return;}
        byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
    }

    public List<Map<String,Object>> sourceSyncStatus() {return sourceSync.status();}

    public void add(String source, String title, String content) {
        requireCompatibleStorage();
        vectorPersistenceService.add(source, vectorRow(title, content));
    }

    public void add(String source,String title,String content,String actor) {
        requireCompatibleStorage();
        vectorPersistenceService.add(source,vectorRow(title,content),actor);
    }

    public boolean update(Long id, String title, String content) {
        requireCompatibleStorage();
        return vectorPersistenceService.update(id, vectorRow(title, content));
    }

    public boolean update(Long id, String title, String content, long expectedRevision, String actor, String reason) {
        requireCompatibleStorage();
        vectorPersistenceService.assertRevision(id, expectedRevision);
        return vectorPersistenceService.update(id, vectorRow(title, content), expectedRevision, actor, reason);
    }

    public KnowledgeVectorPersistenceService.KnowledgeVectorRow prepareContent(String title, String content) {
        requireCompatibleStorage();
        return vectorRow(title, content);
    }

    @Transactional
    public boolean delete(Long id) {
        return jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id = ?", id) > 0;
    }

    public void delete(Long id, long expectedRevision, String actor) {
        vectorPersistenceService.delete(id,expectedRevision,actor);
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        // Model calls stay outside DB locks. If publication races with a query,
        // discard the old-space result (or dimension error) and retry once.
        for (int attempt = 0; attempt < 2; attempt++) {
            String signature = activeSignature();
            try {
                var result = searchWithSignature(query, limit, signature);
                if (signature.equals(activeSignature())) return result;
            } catch (RuntimeException failure) {
                if (signature.equals(activeSignature())) throw failure;
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "向量模型正在切换，请稍后重试检索。");
    }

    private List<KnowledgeSnippet> searchWithSignature(String query, int limit, String signature) {
        boolean vectorReady = storageMatchesConfiguration() && !hasStaleEmbeddings(signature);
        String vector = vectorReady ? embeddingService.toPgVector(embeddingService.embedForSignature(query, signature)) : null;
        String category = inferCategory(query, query);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        int candidateLimit = Math.max(safeLimit, safeLimit * Math.max(2, properties.getKnowledge().getRerankCandidateMultiplier()));
        candidateLimit = Math.min(100, candidateLimit);
        List<String> focusTerms = KnowledgeQueryFocus.terms(query);
        double vectorWeight = vectorReady ? normalizedWeight(properties.getKnowledge().getVectorWeight()) : 0.0;
        double keywordWeight = normalizedWeight(properties.getKnowledge().getKeywordWeight());
        double categoryWeight = normalizedWeight(properties.getKnowledge().getCategoryWeight());
        double totalWeight = vectorWeight + keywordWeight + categoryWeight;
        if (totalWeight <= 0.0) {
            vectorWeight = vectorReady ? 0.65 : 0.0;
            keywordWeight = vectorReady ? 0.25 : 0.75;
            categoryWeight = vectorReady ? 0.10 : 0.25;
            totalWeight = 1.0;
        }
        vectorWeight /= totalWeight;
        keywordWeight /= totalWeight;
        categoryWeight /= totalWeight;
        double focusWeight = focusTerms.isEmpty() ? 0.0 : keywordWeight * 0.5;
        double titleWeight = (keywordWeight-focusWeight) / 2.0;
        double contentWeight = (keywordWeight-focusWeight) / 2.0;
        int rrfK = Math.max(1, Math.min(1000, properties.getKnowledge().getRrfK()));
        double normalizationFactor = rrfK + 1.0;
        String vectorCandidates = vectorReady ? """
                SELECT id, ROW_NUMBER() OVER (ORDER BY distance, id) AS vector_rank
                FROM (
                    SELECT id, embedding <=> %s AS distance FROM knowledge_chunks
                    WHERE embedding_signature=? ORDER BY distance, id LIMIT ?
                ) ranked_vector
                """.formatted(compressionService.castParameter())
                : "SELECT NULL::bigint AS id, NULL::bigint AS vector_rank WHERE false";
        List<Object> args = new ArrayList<>();
        args.add(candidateLimit);
        args.add(focusTerms.toArray(String[]::new));
        if (vectorReady) { args.add(vector); args.add(signature); args.add(candidateLimit); }
        args.addAll(List.of(query, candidateLimit, query, candidateLimit, normalizationFactor,
                vectorWeight, rrfK, titleWeight, rrfK, contentWeight, rrfK, focusWeight, rrfK, category, categoryWeight, rrfK, safeLimit));
        // The focus route is independent of ANN/full-question trigram candidates. It can recover a
        // rare action term whose document otherwise never enters the final reranking pool.
        List<KnowledgeSnippet> snippets = jdbcTemplate.query("""
                WITH focus_limit AS (SELECT ?::int AS n), query_terms AS (
                    SELECT DISTINCT unnest(?::text[]) AS term
                ), focus_matches AS (
                    SELECT k.id, t.term,
                           CASE WHEN POSITION(t.term IN lower(COALESCE(k.title,'')))>0 THEN 3.0 ELSE 1.0 END AS strength
                    FROM knowledge_chunks k JOIN query_terms t
                      ON POSITION(t.term IN lower(COALESCE(k.title,'')))>0
                      OR POSITION(t.term IN lower(split_part(k.content,'参考：',1)))>0
                ), term_weights AS (
                    SELECT term, ln(1.0 + ((SELECT COUNT(*) FROM knowledge_chunks)+1.0)/(COUNT(*)+1.0)) AS idf
                    FROM focus_matches GROUP BY term
                ), focus_scores AS (
                    SELECT m.id,SUM(m.strength*w.idf) AS score
                    FROM focus_matches m JOIN term_weights w ON m.term=w.term GROUP BY m.id
                ), focus_candidates AS (
                    SELECT id,ROW_NUMBER() OVER (ORDER BY score DESC,id) AS focus_rank
                    FROM (SELECT id,score FROM focus_scores ORDER BY score DESC,id LIMIT (SELECT n FROM focus_limit)) f
                ), vector_candidates AS (
                    %s
                ), keyword_title_candidates AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY distance, id) AS title_rank
                    FROM (
                        SELECT id, title <-> ? AS distance
                        FROM knowledge_chunks
                        WHERE title IS NOT NULL
                        ORDER BY distance, id
                        LIMIT ?
                    ) ranked_title
                ), keyword_content_candidates AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY distance, id) AS content_rank
                    FROM (
                        SELECT id, content <-> ? AS distance
                        FROM knowledge_chunks
                        ORDER BY distance, id
                        LIMIT ?
                    ) ranked_content
                ), candidate_ids AS (
                    SELECT id FROM vector_candidates
                    UNION
                    SELECT id FROM keyword_title_candidates
                    UNION
                    SELECT id FROM keyword_content_candidates
                    UNION
                    SELECT id FROM focus_candidates
                ), candidate_ranks AS (
                    SELECT ids.id, vector.vector_rank, title.title_rank, content.content_rank, focus.focus_rank
                    FROM candidate_ids ids
                    LEFT JOIN vector_candidates vector ON vector.id = ids.id
                    LEFT JOIN keyword_title_candidates title ON title.id = ids.id
                    LEFT JOIN keyword_content_candidates content ON content.id = ids.id
                    LEFT JOIN focus_candidates focus ON focus.id = ids.id
                )
                SELECT k.id, k.title, k.content, COALESCE(k.category, 'general') AS category,
                       (? * (
                           CASE WHEN ranks.vector_rank IS NULL THEN 0 ELSE ? / (? + ranks.vector_rank) END
                         + CASE WHEN ranks.title_rank IS NULL THEN 0 ELSE ? / (? + ranks.title_rank) END
                         + CASE WHEN ranks.content_rank IS NULL THEN 0 ELSE ? / (? + ranks.content_rank) END
                         + CASE WHEN ranks.focus_rank IS NULL THEN 0 ELSE ? / (? + ranks.focus_rank) END
                         + CASE WHEN COALESCE(k.category, 'general') = ? THEN ? / (? + 1.0) ELSE 0 END
                       )) AS score
                FROM knowledge_chunks k
                JOIN candidate_ranks ranks ON ranks.id = k.id
                ORDER BY score DESC, k.id ASC
                LIMIT ?
                """.formatted(vectorCandidates), (rs, rowNum) -> new KnowledgeSnippet(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("category"),
                rs.getDouble("score")
        ), args.toArray());
        for (int index = 0; index < snippets.size(); index++) {
            KnowledgeSnippet snippet = snippets.get(index);
            snippet.setRoughScore(snippet.getScore());
            snippet.setRoughRank(index + 1);
        }
        return snippets;
    }

    public String embeddingProviderName() {
        return embeddingService.providerName() + " / " + compressionService.storageType().sqlName();
    }

    public int embeddingDimension() {
        return safeEmbeddingDimension();
    }

    public String retrievalSignature() {
        var knowledge=properties.getKnowledge();
        return String.format(Locale.ROOT,"%s:%s:%.6f:%.6f:%.6f:%d:%d",KnowledgeQueryFocus.VERSION,activeSignature(),
                knowledge.getVectorWeight(),knowledge.getKeywordWeight(),knowledge.getCategoryWeight(),
                knowledge.getRrfK(),knowledge.getRerankCandidateMultiplier());
    }

    private double normalizedWeight(double weight) {
        return Math.max(0.0, weight);
    }

    private KnowledgeVectorPersistenceService.KnowledgeVectorRow vectorRow(String title, String content) {
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        String signature = activeSignature();
        var embedding = embeddingService.embedForSignatureWithUsage(embeddingText(safeTitle, safeContent), signature);
        return new KnowledgeVectorPersistenceService.KnowledgeVectorRow(
                "manual-" + UUID.randomUUID(),
                safeTitle,
                safeContent,
                inferCategory(safeTitle, safeContent),
                embeddingService.toPgVector(embedding.vector()),
                signature,
                chunker.contentHash(safeTitle, safeContent),
                chunker.estimateTokens(safeContent),
                "manual",
                AppTime.now(), embedding.inputTokens(), embedding.totalTokens()
        );
    }

    public boolean storageMatchesConfiguration() {
        EmbeddingColumn column = currentEmbeddingColumn();
        return column != null && column.dimension() == safeEmbeddingDimension()
                && column.storageType().equals(compressionService.storageType().sqlName());
    }

    public String configuredSignature() { return activeSignature(); }

    public boolean hasStaleEmbeddings() {
        return hasStaleEmbeddings(activeSignature());
    }

    private boolean hasStaleEmbeddings(String signature) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_chunks WHERE embedding_signature IS DISTINCT FROM ?)",
                Boolean.class, signature));
    }

    private void requireCompatibleStorage() {
        if (!storageMatchesConfiguration() || hasStaleEmbeddings()) throw new IllegalStateException("向量存储正在等待后台重建，请稍后再试。");
    }

    private EmbeddingColumn currentEmbeddingColumn() {
        String type = jdbcTemplate.queryForObject("""
                SELECT format_type(attribute.atttypid, attribute.atttypmod)
                FROM pg_attribute attribute
                JOIN pg_class table_info ON table_info.oid = attribute.attrelid
                JOIN pg_namespace schema_info ON schema_info.oid = table_info.relnamespace
                WHERE schema_info.nspname = current_schema()
                  AND table_info.relname = 'knowledge_chunks'
                  AND attribute.attname = 'embedding'
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                """, String.class);
        if (type == null) {
            return null;
        }
        Matcher matcher = VECTOR_TYPE.matcher(type);
        return matcher.matches()
                ? new EmbeddingColumn(matcher.group(1), Integer.parseInt(matcher.group(2)))
                : null;
    }

    private int safeEmbeddingDimension() {
        int configured=embeddingService.activeDimension();
        return Math.max(1, Math.min(configured>0?configured:properties.getKnowledge().getEmbeddingDim(), 2000));
    }

    private record ExistingChunk(Long id, String chunkKey, String contentHash, String embeddingSignature,String title,String content) {}
    private record EmbeddingColumn(String storageType, int dimension) {}

    @Transactional
    public void recordHits(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        ids.stream()
                .filter(id -> id != null)
                .distinct()
                .forEach(id -> jdbcTemplate.update("""
                        UPDATE knowledge_chunks
                        SET hit_count = COALESCE(hit_count, 0) + 1,
                            last_hit_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, id));
    }

    public record KnowledgeChunk(String chunkKey,
                                 String title,
                                 String content,
                                 int chunkIndex,
                                 int tokenCount,
                                 String contentHash) {}

    public record SyncResult(int total, int inserted, int updated, int unchanged, int deleted) {}

    public AdminKnowledgeChunkPageResponse listAdminChunks(String keyword,
                                                           String source,
                                                           String sort,
                                                           int page,
                                                           int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        FilterSql filterSql = buildFilterSql(keyword, source);

        StringBuilder sql = new StringBuilder("""
            SELECT id, source, title, content, COALESCE(category, 'general') AS category,
                   COALESCE(token_count, 0) AS token_count, COALESCE(chunk_index, 0) AS chunk_index,
                   embedding_input_tokens, embedding_total_tokens,
                   content_hash, content_revision, COALESCE(source_version, 'v1') AS source_version,
                   source_updated_at, updated_at, COALESCE(hit_count, 0) AS hit_count, last_hit_at
            FROM knowledge_chunks
            """);
        sql.append(filterSql.whereClause());

        if ("hits".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY COALESCE(hit_count, 0) DESC, id ASC");
        } else if ("recentHit".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY last_hit_at DESC NULLS LAST, id ASC");
        } else {
            sql.append(" ORDER BY id ASC");
        }
        sql.append(" LIMIT ? OFFSET ?");

        java.util.List<Object> pageArgs = new java.util.ArrayList<>(filterSql.args());
        pageArgs.add(safeSize);
        pageArgs.add(safePage * safeSize);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunks" + filterSql.whereClause(),
                Long.class,
                filterSql.args().toArray()
        );

        java.util.List<AdminKnowledgeChunkResponse> pageContent = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Long id = rs.getLong("id");
            String chunkSource = rs.getString("source");
            String title = rs.getString("title");
            String content = rs.getString("content");
            String category = rs.getString("category");
            Timestamp lastHitAt = rs.getTimestamp("last_hit_at");
            Timestamp sourceUpdatedAt = rs.getTimestamp("source_updated_at");
            Timestamp updatedAt = rs.getTimestamp("updated_at");

            String preview = content == null ? "" : content;
            if (preview.length() > 120) {
                preview = preview.substring(0, 120) + "...";
            }

            var response = new AdminKnowledgeChunkResponse(
                    id,
                    chunkSource,
                    title,
                    content,
                    preview,
                    category,
                    safeEmbeddingDimension(),
                    content == null ? 0 : content.length(),
                    rs.getInt("token_count"),
                    rs.getInt("chunk_index"),
                    rs.getString("content_hash"),
                    rs.getString("source_version"),
                    sourceUpdatedAt == null ? null : sourceUpdatedAt.toLocalDateTime(),
                    updatedAt == null ? null : updatedAt.toLocalDateTime(),
                    rs.getLong("hit_count"),
                    lastHitAt == null ? null : lastHitAt.toLocalDateTime()
            );
            response.setEmbeddingInputTokens(rs.getObject("embedding_input_tokens", Long.class));
            response.setContentRevision(rs.getLong("content_revision"));
            response.setEmbeddingTotalTokens(rs.getObject("embedding_total_tokens", Long.class));
            return response;
        }, pageArgs.toArray());

        long safeTotal = total == null ? 0 : total;
        int totalPages = safeTotal == 0 ? 0 : (int) Math.ceil((double) safeTotal / safeSize);
        return new AdminKnowledgeChunkPageResponse(pageContent, safeTotal, totalPages, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public List<AdminAssistantStatsResponse.MetricItem> topHitChunks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.query("""
                SELECT COALESCE(NULLIF(title, ''), CONCAT('知识片段 ', id)) AS label,
                       COALESCE(hit_count, 0) AS value
                FROM knowledge_chunks
                WHERE COALESCE(hit_count, 0) > 0
                ORDER BY COALESCE(hit_count, 0) DESC, last_hit_at DESC NULLS LAST, id ASC
                LIMIT ?
                """, (rs, rowNum) -> new AdminAssistantStatsResponse.MetricItem(
                rs.getString("label"),
                rs.getInt("value")
        ), safeLimit);
    }

    private FilterSql buildFilterSql(String keyword, String source) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        java.util.List<String> predicates = new java.util.ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            predicates.add("(LOWER(title) LIKE LOWER(?) OR LOWER(content) LIKE LOWER(?))");
            String value = "%" + keyword.trim() + "%";
            args.add(value);
            args.add(value);
        }

        if (source != null && !source.isBlank() && !"all".equalsIgnoreCase(source)) {
            predicates.add("source = ?");
            args.add(source.trim());
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        return new FilterSql(whereClause, args);
    }

    private record FilterSql(String whereClause, java.util.List<Object> args) {}

    private String inferCategory(String title, String content) {
        return KnowledgeCategoryClassifier.infer(title, content);
    }

    private String embeddingText(String title, String content) {
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        return safeTitle.isBlank() ? safeContent : safeTitle + "\n\n" + safeContent;
    }

    private String safeVersion(String sourceVersion) {
        return sourceVersion == null || sourceVersion.isBlank() ? "v1" : sourceVersion.trim();
    }

    private String activeSignature() {
        return embeddingService.signature() + ":" + compressionService.signatureSuffix();
    }

}
