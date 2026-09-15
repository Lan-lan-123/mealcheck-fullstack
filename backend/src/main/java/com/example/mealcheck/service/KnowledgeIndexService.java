package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class KnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final String SOURCE = "diet_guides.md";
    private static final String SEARCH_CACHE_PREFIX = "rag:search:";
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration SEARCH_CACHE_JITTER = Duration.ofMinutes(2);
    private static final Duration EMPTY_SEARCH_CACHE_TTL = Duration.ofMinutes(1);
    private static final Duration EMPTY_SEARCH_CACHE_JITTER = Duration.ofSeconds(20);
    private static final Duration SEARCH_STALE_TTL = Duration.ofMinutes(30);
    private static final Duration EMPTY_SEARCH_STALE_TTL = Duration.ofMinutes(5);

    private final PgVectorKnowledgeService vectorKnowledgeService;
    private final AppProperties properties;
    private final RedisCacheService redisCacheService;
    private final RagEvaluationService ragEvaluationService;
    private final MarkdownKnowledgeChunker chunker;
    private final ApplicationObservability observability;
    private final KnowledgeRerankerService rerankerService;
    private final KnowledgeResultPostProcessor resultPostProcessor;

    public KnowledgeIndexService(PgVectorKnowledgeService vectorKnowledgeService,
                                 AppProperties properties,
                                 RedisCacheService redisCacheService,
                                 RagEvaluationService ragEvaluationService,
                                 MarkdownKnowledgeChunker chunker,
                                 ApplicationObservability observability,
                                 KnowledgeRerankerService rerankerService,
                                 KnowledgeResultPostProcessor resultPostProcessor) {
        this.vectorKnowledgeService = vectorKnowledgeService;
        this.properties = properties;
        this.redisCacheService = redisCacheService;
        this.ragEvaluationService = ragEvaluationService;
        this.chunker = chunker;
        this.observability = observability;
        this.rerankerService = rerankerService;
        this.resultPostProcessor = resultPostProcessor;
    }

    public long countChunks() {
        return vectorKnowledgeService.count();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void autoIndex() {
        clearSearchCache();
        if (properties.getKnowledge().isAutoIndex()) {
            reindex();
            vectorKnowledgeService.refreshStaleEmbeddings();
        }
    }

    public int reindex() {
        long startedAt = observability.start();
        PgVectorKnowledgeService.SyncResult result = null;
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/diet_guides.md");
            String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(text);
            result = vectorKnowledgeService.syncSource(
                    SOURCE,
                    properties.getKnowledge().getSourceVersion(),
                    LocalDateTime.now(),
                    chunks);
            clearSearchCache();
            observability.recordIndex(startedAt, "success", result);
            log.info("Knowledge index synchronized. source={}, version={}, total={}, inserted={}, updated={}, unchanged={}, deleted={}",
                    SOURCE, properties.getKnowledge().getSourceVersion(), result.total(), result.inserted(),
                    result.updated(), result.unchanged(), result.deleted());
            return result.total();
        } catch (Exception e) {
            observability.recordIndex(startedAt, "failure", result);
            log.error("Knowledge index synchronization failed. source={}, version={}",
                    SOURCE, properties.getKnowledge().getSourceVersion(), e);
            throw new IllegalStateException("知识库索引失败，请确认 PostgreSQL 已安装 pgvector 扩展。", e);
        }
    }

    public List<KnowledgeSnippet> search(String query, int limit) {
        long startedAt = observability.start();
        int safeLimit = Math.max(1, Math.min(limit, 20));
        String key = searchCacheKey(query, safeLimit);
        String cache = "miss";
        try {
            Optional<List<KnowledgeSnippet>> cached = redisCacheService.getJson(
                    key, new TypeReference<List<KnowledgeSnippet>>() {});
            SearchLoad searchLoad;
            if (cached.isPresent()) {
                searchLoad = new SearchLoad(cached.get(), null, "hit");
            } else {
                Optional<List<KnowledgeSnippet>> stale = redisCacheService.getJson(
                        staleKey(key), new TypeReference<List<KnowledgeSnippet>>() {});
                searchLoad = redisCacheService.singleFlight(
                        "load:" + key,
                        () -> loadAndCacheSearch(query, safeLimit, key),
                        stale.map(value -> new SearchLoad(value, null, "stale"))
                );
            }
            cache = searchLoad.cache();
            List<KnowledgeSnippet> snippets = searchLoad.snippets();
            KnowledgeResultPostProcessor.Result processingResult = searchLoad.processingResult();
            if (processingResult == null) {
                ragEvaluationService.recordSearch(query, snippets);
            } else {
                ragEvaluationService.recordSearch(query, snippets, processingResult);
            }
            double topScore = snippets.isEmpty() ? 0.0 : snippets.get(0).getScore();
            observability.recordRagSearch(startedAt, cache, "success", snippets.size(), topScore);
            return snippets;
        } catch (RuntimeException e) {
            observability.recordRagSearch(startedAt, cache, "failure", 0, 0.0);
            throw e;
        }
    }

    private SearchLoad loadAndCacheSearch(String query, int safeLimit, String key) {
        Optional<List<KnowledgeSnippet>> cached = redisCacheService.getJson(
                key, new TypeReference<List<KnowledgeSnippet>>() {});
        if (cached.isPresent()) {
            return new SearchLoad(cached.get(), null, "hit");
        }

        int rerankMultiplier = rerankerService.isConfigured()
                ? Math.max(2, properties.getKnowledge().getRerankCandidateMultiplier())
                : 1;
        int candidateMultiplier = Math.max(resultPostProcessor.candidateMultiplier(), rerankMultiplier);
        int candidateLimit = Math.min(20, safeLimit * candidateMultiplier);
        List<KnowledgeSnippet> candidates = vectorKnowledgeService.search(query, candidateLimit);
        List<KnowledgeSnippet> ranked = rerankerService.rerank(query, candidates, candidateLimit);
        KnowledgeResultPostProcessor.Result processingResult = resultPostProcessor.process(ranked, safeLimit);
        List<KnowledgeSnippet> snippets = List.copyOf(processingResult.snippets());
        observability.recordRagFiltering(
                processingResult.inputCount(),
                snippets.size(),
                processingResult.thresholdFilteredCount(),
                processingResult.duplicateFilteredCount(),
                processingResult.effectiveThreshold());
        observability.recordRagScoreLayers(
                processingResult.topRoughScore(),
                processingResult.topRerankerRawScore(),
                processingResult.topFinalScore(),
                processingResult.rerankerApplied());

        boolean empty = snippets.isEmpty();
        Duration freshTtl = empty ? EMPTY_SEARCH_CACHE_TTL : SEARCH_CACHE_TTL;
        Duration jitter = empty ? EMPTY_SEARCH_CACHE_JITTER : SEARCH_CACHE_JITTER;
        Duration staleTtl = empty ? EMPTY_SEARCH_STALE_TTL : SEARCH_STALE_TTL;
        redisCacheService.setJsonWithJitter(key, snippets, freshTtl, jitter);
        redisCacheService.setJsonWithJitter(staleKey(key), snippets, staleTtl, jitter);
        return new SearchLoad(snippets, processingResult, "miss");
    }

    public void recordHits(List<Long> ids) {
        vectorKnowledgeService.recordHits(ids);
    }

    public void clearSearchCache() {
        redisCacheService.deleteByPrefix(SEARCH_CACHE_PREFIX);
    }

    private String searchCacheKey(String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return SEARCH_CACHE_PREFIX + rerankerService.cacheSignature() + ":"
                + resultPostProcessor.cacheSignature() + ":" + limit + ":" + sha256(normalized);
    }

    private String staleKey(String key) {
        return key + ":stale";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record SearchLoad(List<KnowledgeSnippet> snippets,
                              KnowledgeResultPostProcessor.Result processingResult,
                              String cache) {
    }
}
