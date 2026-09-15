package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.AdminAssistantStatsResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkPageResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import org.springframework.beans.factory.InitializingBean;
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
public class PgVectorKnowledgeService implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeService.class);
    private static final Pattern VECTOR_TYPE = Pattern.compile("(vector|halfvec)\\((\\d+)\\)");
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeEmbeddingService embeddingService;
    private final AppProperties properties;
    private final KnowledgeVectorPersistenceService vectorPersistenceService;
    private final MarkdownKnowledgeChunker chunker;
    private final VectorCompressionService compressionService;

    public PgVectorKnowledgeService(JdbcTemplate jdbcTemplate,
                                    KnowledgeEmbeddingService embeddingService,
                                    AppProperties properties,
                                    KnowledgeVectorPersistenceService vectorPersistenceService,
                                    MarkdownKnowledgeChunker chunker,
                                    VectorCompressionService compressionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.vectorPersistenceService = vectorPersistenceService;
        this.chunker = chunker;
        this.compressionService = compressionService;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        int embeddingDimension = safeEmbeddingDimension();
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id BIGSERIAL PRIMARY KEY,
                    source VARCHAR(255) NOT NULL,
                    title TEXT,
                    content TEXT NOT NULL,
                    embedding %s NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(compressionService.columnType(embeddingDimension)));
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS hit_count BIGINT DEFAULT 0");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS last_hit_at TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS category VARCHAR(64) DEFAULT 'general'");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS embedding_signature VARCHAR(255)");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS chunk_key VARCHAR(160)");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS chunk_index INTEGER DEFAULT 0");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS content_hash CHAR(64)");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS token_count INTEGER DEFAULT 0");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS source_version VARCHAR(64) DEFAULT 'v1'");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE knowledge_chunks ALTER COLUMN title TYPE TEXT");
        migrateEmbeddingStorageIfRequired(embeddingDimension);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding ON knowledge_chunks USING ivfflat (embedding "
                + compressionService.cosineOperatorClass() + ") WITH (lists = 100)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_title_trgm_gist ON knowledge_chunks USING gist (title gist_trgm_ops)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_content_trgm_gist ON knowledge_chunks USING gist (content gist_trgm_ops)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_knowledge_chunks_source_key ON knowledge_chunks(source, chunk_key) WHERE chunk_key IS NOT NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_content_hash ON knowledge_chunks(content_hash)");
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_chunks", Long.class);
        return count == null ? 0 : count;
    }

    public SyncResult syncSource(String source,
                                 String sourceVersion,
                                 LocalDateTime sourceUpdatedAt,
                                 List<KnowledgeChunk> chunks) {
        List<ExistingChunk> stored = jdbcTemplate.query("""
                SELECT id, chunk_key, content_hash, embedding_signature
                FROM knowledge_chunks
                WHERE source = ?
                ORDER BY id
                """, (rs, rowNum) -> new ExistingChunk(
                rs.getLong("id"), rs.getString("chunk_key"), rs.getString("content_hash"),
                rs.getString("embedding_signature")), source);
        Map<String, ExistingChunk> byKey = new HashMap<>();
        Set<Long> staleIds = new HashSet<>();
        for (ExistingChunk existing : stored) {
            staleIds.add(existing.id());
            if (existing.chunkKey() != null && !existing.chunkKey().isBlank()) {
                byKey.put(existing.chunkKey(), existing);
            }
        }

        String signature = activeSignature();
        List<KnowledgeVectorPersistenceService.KnowledgeSyncRow> prepared = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (KnowledgeChunk chunk : chunks) {
            ExistingChunk existing = byKey.get(chunk.chunkKey());
            if (existing != null) {
                staleIds.remove(existing.id());
            }
            boolean needsVector = existing == null
                    || !Objects.equals(existing.contentHash(), chunk.contentHash())
                    || !Objects.equals(existing.embeddingSignature(), signature);
            String vector = needsVector
                    ? embeddingService.toPgVector(embeddingService.embed(embeddingText(chunk.title(), chunk.content())))
                    : null;
            prepared.add(new KnowledgeVectorPersistenceService.KnowledgeSyncRow(
                    existing == null ? null : existing.id(), chunk.chunkKey(), chunk.chunkIndex(), chunk.title(),
                    chunk.content(), inferCategory(chunk.title(), chunk.content()), vector, signature,
                    chunk.contentHash(), chunk.tokenCount(), safeVersion(sourceVersion), sourceUpdatedAt));
            if (existing == null) {
                inserted++;
            } else if (needsVector) {
                updated++;
            } else {
                unchanged++;
            }
        }

        vectorPersistenceService.syncSource(source, prepared, new ArrayList<>(staleIds));
        return new SyncResult(chunks.size(), inserted, updated, unchanged, staleIds.size());
    }

    public void add(String source, String title, String content) {
        vectorPersistenceService.add(source, vectorRow(title, content));
    }

    public boolean update(Long id, String title, String content) {
        return vectorPersistenceService.update(id, vectorRow(title, content));
    }

    @Transactional
    public boolean delete(Long id) {
        return jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE id = ?", id) > 0;
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        String vector = embeddingService.toPgVector(embeddingService.embed(query));
        String category = inferCategory(query, query);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        int candidateLimit = Math.max(safeLimit, safeLimit * Math.max(2, properties.getKnowledge().getRerankCandidateMultiplier()));
        double vectorWeight = normalizedWeight(properties.getKnowledge().getVectorWeight());
        double keywordWeight = normalizedWeight(properties.getKnowledge().getKeywordWeight());
        double categoryWeight = normalizedWeight(properties.getKnowledge().getCategoryWeight());
        double totalWeight = vectorWeight + keywordWeight + categoryWeight;
        if (totalWeight <= 0.0) {
            vectorWeight = 0.65;
            keywordWeight = 0.25;
            categoryWeight = 0.10;
            totalWeight = 1.0;
        }
        vectorWeight /= totalWeight;
        keywordWeight /= totalWeight;
        categoryWeight /= totalWeight;
        double titleWeight = keywordWeight / 2.0;
        double contentWeight = keywordWeight / 2.0;
        int rrfK = Math.max(1, Math.min(1000, properties.getKnowledge().getRrfK()));
        double normalizationFactor = rrfK + 1.0;
        List<KnowledgeSnippet> snippets = jdbcTemplate.query("""
                WITH vector_candidates AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY distance, id) AS vector_rank
                    FROM (
                        SELECT id, embedding <=> %s AS distance
                        FROM knowledge_chunks
                        ORDER BY distance, id
                        LIMIT ?
                    ) ranked_vector
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
                ), candidate_ranks AS (
                    SELECT ids.id, vector.vector_rank, title.title_rank, content.content_rank
                    FROM candidate_ids ids
                    LEFT JOIN vector_candidates vector ON vector.id = ids.id
                    LEFT JOIN keyword_title_candidates title ON title.id = ids.id
                    LEFT JOIN keyword_content_candidates content ON content.id = ids.id
                )
                SELECT k.id, k.title, k.content, COALESCE(k.category, 'general') AS category,
                       (? * (
                           CASE WHEN ranks.vector_rank IS NULL THEN 0 ELSE ? / (? + ranks.vector_rank) END
                         + CASE WHEN ranks.title_rank IS NULL THEN 0 ELSE ? / (? + ranks.title_rank) END
                         + CASE WHEN ranks.content_rank IS NULL THEN 0 ELSE ? / (? + ranks.content_rank) END
                         + CASE WHEN COALESCE(k.category, 'general') = ? THEN ? / (? + 1.0) ELSE 0 END
                       )) AS score
                FROM knowledge_chunks k
                JOIN candidate_ranks ranks ON ranks.id = k.id
                ORDER BY score DESC, k.id ASC
                LIMIT ?
                """.formatted(compressionService.castParameter()), (rs, rowNum) -> new KnowledgeSnippet(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("category"),
                rs.getDouble("score")
        ), vector, candidateLimit, query, candidateLimit, query, candidateLimit,
                normalizationFactor,
                vectorWeight, rrfK,
                titleWeight, rrfK,
                contentWeight, rrfK,
                category, categoryWeight, rrfK,
                safeLimit);
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

    public int refreshStaleEmbeddings() {
        String signature = activeSignature();
        List<StoredKnowledge> stale = jdbcTemplate.query(
                "SELECT id, title, content FROM knowledge_chunks WHERE embedding_signature IS DISTINCT FROM ? ORDER BY id",
                (rs, rowNum) -> new StoredKnowledge(rs.getLong("id"), rs.getString("title"), rs.getString("content")),
                signature
        );
        if (stale.isEmpty()) {
            return 0;
        }
        List<KnowledgeVectorPersistenceService.KnowledgeVectorUpdate> updates = stale.stream()
                .map(chunk -> new KnowledgeVectorPersistenceService.KnowledgeVectorUpdate(
                        chunk.id(), embeddingService.toPgVector(embeddingService.embed(
                                embeddingText(chunk.title(), chunk.content())))))
                .toList();
        vectorPersistenceService.updateEmbeddings(updates, signature);
        log.info("Refreshed {} stale knowledge embeddings using {}", updates.size(), embeddingService.providerName());
        return updates.size();
    }

    private double normalizedWeight(double weight) {
        return Math.max(0.0, weight);
    }

    private KnowledgeVectorPersistenceService.KnowledgeVectorRow vectorRow(String title, String content) {
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        float[] embedding = embeddingService.embed(embeddingText(safeTitle, safeContent));
        return new KnowledgeVectorPersistenceService.KnowledgeVectorRow(
                "manual-" + UUID.randomUUID(),
                safeTitle,
                safeContent,
                inferCategory(safeTitle, safeContent),
                embeddingService.toPgVector(embedding),
                activeSignature(),
                chunker.contentHash(safeTitle, safeContent),
                chunker.estimateTokens(safeContent),
                "manual",
                LocalDateTime.now()
        );
    }

    private void migrateEmbeddingStorageIfRequired(int targetDimension) {
        EmbeddingColumn current = currentEmbeddingColumn();
        String targetStorage = compressionService.storageType().sqlName();
        if (current == null || (current.dimension() == targetDimension && current.storageType().equals(targetStorage))) {
            return;
        }

        boolean reuseExistingValues = current.dimension() == targetDimension;
        log.info("Migrating knowledge embedding storage from {}({}) to {}({}); reuseExistingValues={}",
                current.storageType(), current.dimension(), targetStorage, targetDimension, reuseExistingValues);
        List<KnowledgeVectorPersistenceService.KnowledgeVectorUpdate> updates = List.of();
        if (!reuseExistingValues) {
            List<StoredKnowledge> storedKnowledge = jdbcTemplate.query(
                    "SELECT id, title, content FROM knowledge_chunks ORDER BY id",
                    (rs, rowNum) -> new StoredKnowledge(
                            rs.getLong("id"), rs.getString("title"), rs.getString("content"))
            );
            updates = storedKnowledge.stream()
                    .map(chunk -> new KnowledgeVectorPersistenceService.KnowledgeVectorUpdate(
                            chunk.id(), embeddingService.toPgVector(embeddingService.embed(
                                    embeddingText(chunk.title(), chunk.content())))))
                    .toList();
        }
        vectorPersistenceService.migrateStorage(
                targetDimension, reuseExistingValues, updates, activeSignature());
        log.info("Knowledge embedding storage migration completed. regenerated={}, estimatedBytesPerVector={}",
                updates.size(), compressionService.estimatedBytesPerVector(targetDimension));
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
        return Math.max(1, Math.min(properties.getKnowledge().getEmbeddingDim(), 2000));
    }

    private record StoredKnowledge(Long id, String title, String content) {}
    private record ExistingChunk(Long id, String chunkKey, String contentHash, String embeddingSignature) {}
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
                   content_hash, COALESCE(source_version, 'v1') AS source_version,
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

            return new AdminKnowledgeChunkResponse(
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
        String text = ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "减脂", "减肥", "控卡", "热量", "fat")) return "fat_loss";
        if (containsAny(text, "增肌", "蛋白", "肌肉", "训练", "muscle")) return "muscle_gain";
        if (containsAny(text, "油炸", "高油", "炸鸡", "烧烤", "烤肉", "红烧")) return "high_oil";
        if (containsAny(text, "甜", "含糖", "奶茶", "饮料", "糖")) return "sugar";
        if (containsAny(text, "蔬菜", "纤维", "绿叶菜", "维生素")) return "vegetable";
        if (containsAny(text, "主食", "米饭", "面", "碳水", "红薯")) return "staple";
        return "general";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
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
