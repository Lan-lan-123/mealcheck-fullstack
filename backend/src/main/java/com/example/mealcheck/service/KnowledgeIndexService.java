package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public int reindex() {
        return synchronizeSource(true, activeSplitterStrategy());
    }
    public int reindex(String splitterStrategy) {
        return synchronizeSource(true, MarkdownKnowledgeChunker.normalizeStrategy(splitterStrategy));
    }
    public String activeSplitterStrategy() {
        String stored = vectorKnowledgeService.splitterStrategy(SOURCE);
        return MarkdownKnowledgeChunker.normalizeStrategy(stored == null ? properties.getKnowledge().getSplitterStrategy() : stored);
    }
    public int initialize() {
        return synchronizeSource(false, activeSplitterStrategy());
    }
    private int synchronizeSource(boolean explicit, String splitterStrategy) {
        return synchronizeSource(explicit,splitterStrategy,null,null,null);
    }
    public int reindex(String strategy,Long expectedRevision,KnowledgeRebuildStore.Work work,String expectedCorpusSha) {
        return synchronizeSource(true,MarkdownKnowledgeChunker.normalizeStrategy(strategy),expectedRevision,work,expectedCorpusSha);
    }
    private int synchronizeSource(boolean explicit, String splitterStrategy,Long expectedRevision,KnowledgeRebuildStore.Work work,String expectedCorpusSha) {
        long startedAt = observability.start();
        PgVectorKnowledgeService.SyncResult result = null;
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/diet_guides.md");
            String text;
            try(var input=resource.getInputStream()) {text=new String(input.readAllBytes(), StandardCharsets.UTF_8);}
            if(expectedCorpusSha!=null&&!expectedCorpusSha.equals(sha256(text)))
                throw new KnowledgeJobFailure.EmbeddingException(new KnowledgeJobFailure("PREVIEW_STALE","源文件在预览后发生变化，请重新预览并提交。",false));
            List<PgVectorKnowledgeService.KnowledgeChunk> chunks = chunker.split(text, splitterStrategy);
            result = expectedRevision==null&&work==null ? vectorKnowledgeService.syncSource(
                    SOURCE,
                    properties.getKnowledge().getSourceVersion(),
                    AppTime.now(),
                    chunks,explicit,splitterStrategy) : vectorKnowledgeService.syncSource(SOURCE,properties.getKnowledge().getSourceVersion(),AppTime.now(),chunks,explicit,splitterStrategy,expectedRevision,work);
            // Cache revisions already advance atomically with writes. A startup no-op
            // must not evict valid caches across the whole cluster.
            observability.recordIndex(startedAt, "success", result);
            log.info("Knowledge index synchronized. source={}, version={}, total={}, inserted={}, updated={}, unchanged={}, deleted={}",
                    SOURCE, properties.getKnowledge().getSourceVersion(), result.total(), result.inserted(),
                    result.updated(), result.unchanged(), result.deleted());
            return result.total();
        } catch (org.springframework.web.server.ResponseStatusException deferred) {
            observability.recordIndex(startedAt,"deferred",result);
            throw deferred;
        } catch (Exception e) {
            observability.recordIndex(startedAt, "failure", result);
            log.error("Knowledge index synchronization failed. source={}, version={}",
                    SOURCE, properties.getKnowledge().getSourceVersion(), e);
            throw new IllegalStateException("知识库索引失败，请确认 PostgreSQL 已安装 pgvector 扩展。", e);
        }
    }

    public List<java.util.Map<String,Object>> sourceSyncStatus() {return vectorKnowledgeService.sourceSyncStatus();}

    public List<KnowledgeSnippet> search(String query, int limit) {
        AiCallContext.checkExecution();
        if (query == null || query.isBlank()) return List.of();
        long timingStartedAt=System.nanoTime();
        long startedAt = observability.start();
        int safeLimit = Math.max(1, Math.min(limit, 20));
        String key = searchCacheKey(query, safeLimit);
        String cache = "miss";
        boolean timingSuccess=false;
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
            AiCallContext.checkExecution();
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
            timingSuccess=true;
            return snippets;
        } catch (RuntimeException e) {
            observability.recordRagSearch(startedAt, cache, "failure", 0, 0.0);
            throw e;
        } finally {
            log.info("Assistant phase timing. phase=retrieval, elapsedMicros={}, outcome={}, cache={}",
                    Math.max(0,System.nanoTime()-timingStartedAt)/1000,timingSuccess?"success":"failure",cache);
        }
    }

    private SearchLoad loadAndCacheSearch(String query, int safeLimit, String key) {
        Optional<List<KnowledgeSnippet>> cached = redisCacheService.getJson(
                key, new TypeReference<List<KnowledgeSnippet>>() {});
        if (cached.isPresent()) {
            return new SearchLoad(cached.get(), null, "hit");
        }

        KnowledgeResultPostProcessor.Result processingResult = retrieve(query, safeLimit);
        AiCallContext.checkExecution();
        List<KnowledgeSnippet> snippets = List.copyOf(processingResult.snippets());
        observability.recordRagFiltering(
                processingResult.inputCount(), snippets.size(), processingResult.thresholdFilteredCount(),
                processingResult.duplicateFilteredCount(), processingResult.effectiveThreshold());
        observability.recordRagScoreLayers(processingResult.topRoughScore(), processingResult.topRerankerRawScore(),
                processingResult.topFinalScore(), processingResult.rerankerApplied());

        boolean empty = snippets.isEmpty();
        Duration freshTtl = empty ? EMPTY_SEARCH_CACHE_TTL : SEARCH_CACHE_TTL;
        Duration jitter = empty ? EMPTY_SEARCH_CACHE_JITTER : SEARCH_CACHE_JITTER;
        Duration staleTtl = empty ? EMPTY_SEARCH_STALE_TTL : SEARCH_STALE_TTL;
        // Do not cache fallback results, including an empty result after fallback filtering.
        if (!rerankerService.isConfigured() || processingResult.rerankerApplied() || processingResult.inputCount() == 0) {
            AiCallContext.checkExecution();
            redisCacheService.setJsonWithJitter(key, snippets, freshTtl, jitter);
            AiCallContext.checkExecution();
            redisCacheService.setJsonWithJitter(staleKey(key), snippets, staleTtl, jitter);
        }
        return new SearchLoad(snippets, processingResult, "miss");
    }

    /** Same retrieval/reranking/filtering as a fresh production search, without cache or user statistics. */
    public List<KnowledgeSnippet> searchForEvaluation(String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 500 || limit < 1 || limit > 5)
            throw new IllegalArgumentException("Invalid evaluation knowledge query");
        return List.copyOf(retrieve(query, limit).snippets());
    }

    private KnowledgeResultPostProcessor.Result retrieve(String query, int safeLimit) {
        int rerankMultiplier = rerankerService.isConfigured()
                ? Math.max(2, properties.getKnowledge().getRerankCandidateMultiplier())
                : 1;
        int candidateMultiplier = Math.max(resultPostProcessor.candidateMultiplier(), rerankMultiplier);
        int candidateLimit = Math.min(20, safeLimit * candidateMultiplier);
        List<KnowledgeSnippet> candidates = vectorKnowledgeService.search(query, candidateLimit);
        AiCallContext.checkExecution();
        List<KnowledgeSnippet> ranked = rerankerService.rerank(query, candidates, candidateLimit);
        AiCallContext.checkExecution();
        return resultPostProcessor.process(ranked, safeLimit);
    }

    public void recordHits(List<Long> ids) {
        vectorKnowledgeService.recordHits(ids);
    }

    public void clearSearchCache() {
        redisCacheService.deleteByPrefix(SEARCH_CACHE_PREFIX);
    }

    private String searchCacheKey(String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        // The revision commits in the same transaction as knowledge changes. An older search
        // may finish later, but it can only populate its old namespace, including stale results.
        return SEARCH_CACHE_PREFIX + "complete-v2:v" + vectorKnowledgeService.searchRevision() + ":" + vectorKnowledgeService.retrievalSignature() + ":" + rerankerService.cacheSignature() + ":"
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
