package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class AssistantLongTermMemoryService {
    private static final Logger log = LoggerFactory.getLogger(AssistantLongTermMemoryService.class);
    private static final List<String> MEMORY_MARKERS = List.of(
            "我喜欢", "我不喜欢", "我不吃", "我不能吃", "我对", "我想", "我的目标", "过敏",
            "减脂", "增肌", "控糖", "清淡", "素食", "乳糖不耐", "忌口");

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeEmbeddingService embeddingService;
    private final VectorCompressionService compressionService;
    private final AppProperties properties;
    private final AssistantMemoryStore store;

    public AssistantLongTermMemoryService(JdbcTemplate jdbcTemplate,
                                          KnowledgeEmbeddingService embeddingService,
                                          VectorCompressionService compressionService,
                                          AppProperties properties, AssistantMemoryStore store) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.compressionService = compressionService;
        this.properties = properties;
        this.store = store;
    }

    private String signature() { return embeddingService.signature() + ":" + compressionService.signatureSuffix(); }

    private List<MemoryFact> textRecall(UserAccount user, AssistantConversation conversation, int limit) {
        return jdbcTemplate.query("""
                SELECT fact_type,fact_text,confidence FROM assistant_memory_facts WHERE user_id=?
                ORDER BY CASE WHEN conversation_id=? THEN 0 ELSE 1 END,last_seen_at DESC,id DESC LIMIT ?
                """, (rs,n)->new MemoryFact(rs.getString(1),rs.getString(2),rs.getDouble(3),0,0.35),
                user.getId(),conversation==null?null:conversation.getId(),Math.max(1,Math.min(10,limit)));
    }

    public List<MemoryFact> recall(UserAccount user,
                                   AssistantConversation conversation,
                                   String query,
                                   int limit) {
        if (user == null || user.getId() == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            if (!store.ready(signature())) return textRecall(user, conversation, limit);
            String vector = embeddingService.toPgVector(embeddingService.embed(query));
            int safeLimit = Math.max(1, Math.min(10, limit));
            Long conversationId = conversation == null ? null : conversation.getId();
            return jdbcTemplate.query("""
                    SELECT fact_type, fact_text, confidence,
                           GREATEST(0.0, 1.0 - (coalesce(v.embedding,v.embedding_half::vector) <=> CAST(? AS vector))) AS semantic_score,
                           (0.82 * GREATEST(0.0, 1.0 - (coalesce(v.embedding,v.embedding_half::vector) <=> CAST(? AS vector)))
                            + 0.13 * EXP(-EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - last_seen_at)) / 7776000.0)
                            + CASE WHEN conversation_id = ? THEN 0.05 ELSE 0.0 END) AS final_score
                    FROM assistant_memory_facts f JOIN assistant_memory_vectors v ON v.fact_id=f.id AND v.input_hash=f.fact_hash
                    JOIN assistant_memory_version active ON active.id=1 AND active.signature=v.signature
                    WHERE f.user_id = ? AND v.signature = ?
                    ORDER BY final_score DESC, last_seen_at DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new MemoryFact(
                            rs.getString("fact_type"), rs.getString("fact_text"),
                            rs.getDouble("confidence"), rs.getDouble("semantic_score"),
                            rs.getDouble("final_score")),
                    vector, vector, conversationId, user.getId(), signature(), safeLimit)
                    .stream().filter(fact -> fact.finalScore() >= 0.35).toList();
        } catch (Exception e) {
            log.warn("Long-term memory recall degraded: {}", e.getMessage());
            try { return textRecall(user, conversation, limit); }
            catch (RuntimeException unavailable) { return List.of(); }
        }
    }

    public int remember(UserAccount user, AssistantConversation conversation, String userText) {
        if (user == null || user.getId() == null || conversation == null || conversation.getId() == null) {
            return 0;
        }
        int stored = 0;
        for (ExtractedFact fact : extractFacts(userText)) {
            try {
                String hash=sha256(fact.text());
                long id=store.saveText(user.getId(),conversation.getId(),fact.type(),fact.text(),hash,fact.confidence());
                stored++;
                if (store.ready(signature())) {
                    String vector=embeddingService.toPgVector(embeddingService.embed(fact.text()));
                    store.saveVector(id,hash,signature(),properties.getKnowledge().getEmbeddingDim(),compressionService.storageType().sqlName(),vector);
                }
            } catch (Exception e) {
                // A failed embedding cannot roll back already saved preference/restriction text.
                log.warn("Long-term memory vector write deferred: {}", e.getClass().getSimpleName());
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
