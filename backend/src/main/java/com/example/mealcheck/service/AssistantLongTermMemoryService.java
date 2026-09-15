package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@DependsOnDatabaseInitialization
public class AssistantLongTermMemoryService implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(AssistantLongTermMemoryService.class);
    private static final List<String> MEMORY_MARKERS = List.of(
            "我喜欢", "我不喜欢", "我不吃", "我不能吃", "我对", "我想", "我的目标", "过敏",
            "减脂", "增肌", "控糖", "清淡", "素食", "乳糖不耐", "忌口");

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeEmbeddingService embeddingService;
    private final VectorCompressionService compressionService;
    private final AppProperties properties;

    public AssistantLongTermMemoryService(JdbcTemplate jdbcTemplate,
                                          KnowledgeEmbeddingService embeddingService,
                                          VectorCompressionService compressionService,
                                          AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.compressionService = compressionService;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        int dimension = Math.max(1, properties.getKnowledge().getEmbeddingDim());
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS assistant_memory_facts (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    conversation_id BIGINT REFERENCES assistant_conversations(id) ON DELETE CASCADE,
                    fact_type VARCHAR(32) NOT NULL,
                    fact_text TEXT NOT NULL,
                    fact_hash CHAR(64) NOT NULL,
                    embedding %s NOT NULL,
                    embedding_signature VARCHAR(255) NOT NULL,
                    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.7,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(compressionService.columnType(dimension)));
        migrateEmbeddingStorageIfRequired(dimension);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_assistant_memory_user_hash "
                + "ON assistant_memory_facts(user_id, fact_hash)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_assistant_memory_user_seen "
                + "ON assistant_memory_facts(user_id, last_seen_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_assistant_memory_embedding ON assistant_memory_facts "
                + "USING ivfflat (embedding " + compressionService.cosineOperatorClass() + ") WITH (lists = 50)");
    }

    private void migrateEmbeddingStorageIfRequired(int dimension) {
        String currentType = jdbcTemplate.queryForObject("""
                SELECT format_type(attribute.atttypid, attribute.atttypmod)
                FROM pg_attribute attribute
                JOIN pg_class relation ON relation.oid = attribute.attrelid
                WHERE relation.relname = 'assistant_memory_facts'
                  AND attribute.attname = 'embedding'
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                """, String.class);
        String expectedType = compressionService.columnType(dimension);
        if (currentType == null || currentType.equalsIgnoreCase(expectedType)) {
            return;
        }
        log.warn("Resetting derived long-term memory vectors after storage change: {} -> {}",
                currentType, expectedType);
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_assistant_memory_embedding");
        jdbcTemplate.execute("TRUNCATE TABLE assistant_memory_facts");
        jdbcTemplate.execute("ALTER TABLE assistant_memory_facts DROP COLUMN embedding");
        jdbcTemplate.execute("ALTER TABLE assistant_memory_facts ADD COLUMN embedding "
                + expectedType + " NOT NULL");
    }

    public List<MemoryFact> recall(UserAccount user,
                                   AssistantConversation conversation,
                                   String query,
                                   int limit) {
        if (user == null || user.getId() == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String vector = embeddingService.toPgVector(embeddingService.embed(query));
            int safeLimit = Math.max(1, Math.min(10, limit));
            Long conversationId = conversation == null ? null : conversation.getId();
            return jdbcTemplate.query("""
                    SELECT fact_type, fact_text, confidence,
                           GREATEST(0.0, 1.0 - (embedding <=> %s)) AS semantic_score,
                           (0.82 * GREATEST(0.0, 1.0 - (embedding <=> %s))
                            + 0.13 * EXP(-EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - last_seen_at)) / 7776000.0)
                            + CASE WHEN conversation_id = ? THEN 0.05 ELSE 0.0 END) AS final_score
                    FROM assistant_memory_facts
                    WHERE user_id = ? AND embedding_signature = ?
                    ORDER BY final_score DESC, last_seen_at DESC
                    LIMIT ?
                    """.formatted(compressionService.castParameter(), compressionService.castParameter()),
                    (rs, rowNum) -> new MemoryFact(
                            rs.getString("fact_type"), rs.getString("fact_text"),
                            rs.getDouble("confidence"), rs.getDouble("semantic_score"),
                            rs.getDouble("final_score")),
                    vector, vector, conversationId, user.getId(), embeddingService.signature(), safeLimit)
                    .stream().filter(fact -> fact.finalScore() >= 0.35).toList();
        } catch (Exception e) {
            log.warn("Long-term memory recall degraded: {}", e.getMessage());
            return List.of();
        }
    }

    public int remember(UserAccount user, AssistantConversation conversation, String userText) {
        if (user == null || user.getId() == null || conversation == null || conversation.getId() == null) {
            return 0;
        }
        int stored = 0;
        for (ExtractedFact fact : extractFacts(userText)) {
            try {
                String vector = embeddingService.toPgVector(embeddingService.embed(fact.text()));
                stored += jdbcTemplate.update("""
                        INSERT INTO assistant_memory_facts(
                            user_id, conversation_id, fact_type, fact_text, fact_hash,
                            embedding, embedding_signature, confidence, created_at, last_seen_at)
                        VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?, ?)
                        ON CONFLICT (user_id, fact_hash) DO UPDATE SET
                            conversation_id = EXCLUDED.conversation_id,
                            fact_type = EXCLUDED.fact_type,
                            fact_text = EXCLUDED.fact_text,
                            embedding = EXCLUDED.embedding,
                            embedding_signature = EXCLUDED.embedding_signature,
                            confidence = LEAST(1.0, assistant_memory_facts.confidence + 0.05),
                            last_seen_at = EXCLUDED.last_seen_at
                        """.formatted(compressionService.castParameter()),
                        user.getId(), conversation.getId(), fact.type(), fact.text(), sha256(fact.text()),
                        vector, embeddingService.signature(), fact.confidence(),
                        LocalDateTime.now(), LocalDateTime.now());
            } catch (Exception e) {
                log.warn("Long-term memory write degraded: {}", e.getMessage());
            }
        }
        return stored;
    }

    List<ExtractedFact> extractFacts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String sentence : text.split("[。！？!?；;\\n]+")) {
            String normalized = sentence.replaceAll("\\s+", " ").trim();
            boolean explicitUserFact = normalized.contains("我")
                    || normalized.contains("本人") || normalized.contains("目标是");
            if (normalized.length() >= 3 && normalized.length() <= 160 && explicitUserFact
                    && MEMORY_MARKERS.stream().anyMatch(normalized::contains)) {
                unique.add(normalized);
            }
        }
        List<ExtractedFact> facts = new ArrayList<>();
        for (String fact : unique) {
            facts.add(new ExtractedFact(inferType(fact), fact, confidence(fact)));
        }
        return facts;
    }

    private String inferType(String fact) {
        if (containsAny(fact, "过敏", "不能吃", "不吃", "忌口", "乳糖不耐")) return "restriction";
        if (containsAny(fact, "目标", "减脂", "增肌", "控糖", "清淡")) return "goal";
        if (containsAny(fact, "喜欢", "不喜欢", "素食")) return "preference";
        return "profile";
    }

    private double confidence(String fact) {
        return containsAny(fact, "过敏", "不能吃", "我的目标") ? 0.9 : 0.75;
    }

    private boolean containsAny(String text, String... values) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (normalized.contains(value.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash assistant memory", e);
        }
    }

    record ExtractedFact(String type, String text, double confidence) {}

    public record MemoryFact(String type,
                             String text,
                             double confidence,
                             double semanticScore,
                             double finalScore) {}
}
