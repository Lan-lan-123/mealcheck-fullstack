package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagChainReport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Fixture-free local component precheck. This does not execute pgvector or measure semantic quality. */
public final class LocalHashScreeningTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Indexed(PgVectorKnowledgeService.KnowledgeChunk chunk, float[] vector) { }
    private record Ranked(Indexed source, double score) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("CORPUS MANIFEST CASES SUITE OUTPUT_UNDER_LOGS");
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        if (!output.startsWith(root.resolve("logs"))) throw new IllegalArgumentException("Output must remain under logs/");
        Files.createDirectories(output);
        Path resultPath = output.resolve("results.json");
        if (Files.exists(resultPath)) throw new IllegalArgumentException("Existing results must not be overwritten");
        byte[] corpusBytes = Files.readAllBytes(Path.of(args[0]));
        JsonNode manifest = JSON.readTree(Files.readAllBytes(Path.of(args[1])));
        JsonNode cases = JSON.readTree(Files.readAllBytes(Path.of(args[2])));
        JsonNode suite = JSON.readTree(Files.readAllBytes(Path.of(args[3])));
        if (!sha(corpusBytes).equals(manifest.path("corpusSha256").asText())) throw new IllegalArgumentException("Stale corpus manifest");
        if (!canonicalDigest(cases).equals(suite.path("source").path("datasetSha256").asText()))
            throw new IllegalArgumentException("Frozen dataset fingerprint changed");
        String corpus = new String(corpusBytes, StandardCharsets.UTF_8);
        if (!sha(corpus.replace("\r\n", "\n").replace("\r", "\n").getBytes(StandardCharsets.UTF_8))
                .equals(suite.path("source").path("corpusTextSha256").asText())) throw new IllegalArgumentException("Frozen corpus changed");
        var properties = new AppProperties();
        var chunker = new MarkdownKnowledgeChunker(properties);
        var chunks = chunker.split(corpus);
        if (chunks.size() != 1351 || manifest.path("chunkCount").asInt() != chunks.size()
                || !MarkdownKnowledgeChunker.RECURSIVE_REVISION.equals(manifest.path("splitterRevision").asText()))
            throw new IllegalArgumentException("This precheck requires the current 1351-chunk boundary revision");
        for (int i = 0; i < chunks.size(); i++) {
            var actual = chunks.get(i); var declared = manifest.path("chunks").get(i);
            if (!actual.contentHash().equals(declared.path("contentHash").asText())
                    || !actual.chunkKey().equals(declared.path("chunkKey").asText())
                    || !actual.title().equals(declared.path("title").asText())
                    || actual.chunkIndex() != declared.path("chunkIndex").asInt(-1)
                    || !actual.content().equals(declared.path("content").asText())
                    || actual.tokenCount() != declared.path("tokenCount").asInt())
                throw new IllegalArgumentException("Current production splitter differs from manifest at " + i);
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (var row : cases) {
            if (byId.put(row.path("id").asText(), row) != null) throw new IllegalArgumentException("Duplicate question ID");
            RagEvidenceLabels.validate(row, chunks);
        }
        var fixedIds = suite.path("sets").path("screening30").path("caseIds");
        if (fixedIds.size() != 30 || byId.size() != 200) throw new IllegalArgumentException("Expected fixed screening30 and 200 source cases");
        var hash = new HashEmbeddingService(properties);
        var indexed = new ArrayList<Indexed>();
        for (var chunk : chunks) indexed.add(new Indexed(chunk, hash.embed(chunk.title().trim() + "\n\n" + chunk.content().trim())));
        var processor = new KnowledgeResultPostProcessor(properties);
        var filterOffProperties = new AppProperties();
        filterOffProperties.getKnowledge().setResultFilterEnabled(false);
        var filterOff = new KnowledgeResultPostProcessor(filterOffProperties);
        var results = new ArrayList<Map<String,Object>>();
        var reviewPool = new LinkedHashMap<String,Map<String,Object>>();
        int queryCalls = 0, outsideSkipped = 0, filteredCoverage = 0, rawCoverage = 0;
        int unjudgedFilteredHits = 0, firstRankingJudgeable = 0;
        for (var fixedId : fixedIds) {
            String id = fixedId.asText(); var expected = byId.get(id);
            if (expected == null) throw new IllegalArgumentException("Unknown fixed ID " + id);
            String question = expected.path("question").asText();
            var result = new LinkedHashMap<String,Object>();
            result.put("caseId", id); result.put("question", question);
            result.put("answerability", expected.path("answerability").asText());
            boolean outside = expected.path("expectNoAnswer").asBoolean();
            if (outside) {
                outsideSkipped++;
                result.put("state", "SKIPPED_OUT_OF_SCOPE"); result.put("queryEmbeddingCalls", 0);
                result.put("rawCandidates", List.of()); result.put("filtered", RagChainAssessment.assess(expected, List.of()));
                results.add(result); continue;
            }
            long started = System.nanoTime();
            float[] queryVector = hash.embed(question); queryCalls++;
            // Expected labels are intentionally absent from this retrieval function.
            var ranked = rank(indexed, queryVector, 20);
            var candidates = new ArrayList<KnowledgeSnippet>();
            Map<Long,Indexed> sources = new HashMap<>();
            for (int i = 0; i < ranked.size(); i++) {
                var item = ranked.get(i); var chunk = item.source().chunk(); long ordinal = chunk.chunkIndex() + 1L;
                var snippet = new KnowledgeSnippet(ordinal, chunk.title(), chunk.content(),
                        KnowledgeCategoryClassifier.infer(chunk.title(), chunk.content()), item.score());
                snippet.setRoughRank(i + 1); candidates.add(snippet); sources.put(ordinal, item.source());
            }
            var filtered = processor.process(candidates, 5);
            var allRetained = processor.process(candidates, candidates.size());
            var raw = filterOff.process(candidates, 5);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var filteredReport = assess(expected, filtered.snippets(), sources, question, elapsed);
            var rawReport = assess(expected, raw.snippets(), sources, question, elapsed);
            if (filteredReport.finalEvidenceCovered()) filteredCoverage++;
            if (rawReport.finalEvidenceCovered()) rawCoverage++;
            if (filteredReport.judgmentCoverage().primaryRankingComplete()) firstRankingJudgeable++;
            unjudgedFilteredHits += filteredReport.judgmentCoverage().primaryUnjudgedCount();
            Set<Long> retainedIds = new HashSet<>(), finalIds = new HashSet<>(), rawIds = new HashSet<>();
            allRetained.snippets().forEach(s -> retainedIds.add(s.getId()));
            filtered.snippets().forEach(s -> finalIds.add(s.getId())); raw.snippets().forEach(s -> rawIds.add(s.getId()));
            var rawRows = new ArrayList<Map<String,Object>>();
            for (var snippet : candidates) {
                var chunk = sources.get(snippet.getId()).chunk();
                var hit = hit(snippet, chunk);
                String judgment = RagEvidenceLabels.judgment(hit, expected);
                String disposition = finalIds.contains(snippet.getId()) ? "RETAINED_TOP5"
                        : !Double.isFinite(snippet.getScore()) || snippet.getScore() + 1e-9 < filtered.effectiveThreshold() ? "THRESHOLD_FILTERED"
                        : !retainedIds.contains(snippet.getId()) ? "DUPLICATE_FILTERED" : "RESULT_LIMIT";
                var candidate = new LinkedHashMap<String,Object>();
                candidate.put("localOrdinalNotDatabaseId", snippet.getId()); candidate.put("rank", snippet.getRoughRank());
                candidate.put("knowledgeId", chunk.contentHash()); candidate.put("chunkKey", chunk.chunkKey());
                candidate.put("title", snippet.getTitle()); candidate.put("content", snippet.getContent());
                candidate.put("score", snippet.getScore()); candidate.put("existingJudgment", judgment);
                candidate.put("filterDisposition", disposition); candidate.put("unfilteredTop5", rawIds.contains(snippet.getId()));
                rawRows.add(candidate);
                if ("UNJUDGED".equals(judgment) && (finalIds.contains(snippet.getId()) || rawIds.contains(snippet.getId()))) {
                    var pool = new LinkedHashMap<String,Object>(candidate);
                    pool.put("caseId", id); pool.put("question", question);
                    pool.put("proposedRelevance", null); pool.put("reviewStatus", "UNREVIEWED");
                    reviewPool.put(id + ":" + chunk.contentHash(), pool);
                }
            }
            result.put("state", "LOCAL_COMPONENT_PRECHECK_COMPLETE"); result.put("queryEmbeddingCalls", 1);
            result.put("actualQuery", question); result.put("rawCandidates", rawRows);
            result.put("filtered", filteredReport); result.put("unfilteredTop5", rawReport);
            result.put("postprocessing", filtered);
            results.add(result);
        }
        if (queryCalls != 28 || outsideSkipped != 2 || results.size() != 30) throw new IllegalStateException("Fixed scope execution drift");
        var outputData = new LinkedHashMap<String,Object>();
        outputData.put("executionMode", "LOCAL_HASH_VECTOR_ONLY_PRECHECK"); outputData.put("qualityMeasurement", false);
        outputData.put("productionPgVectorUsed", false); outputData.put("providerCalls", 0);
        outputData.put("chatCalls", 0); outputData.put("rerankerCalls", 0); outputData.put("databaseMutations", 0);
        outputData.put("scope", "Production splitter + local HashEmbeddingService + vector-only cosine top20 + production postprocessor and evidence assessor. Excludes PostgreSQL RRF/lexical/focus routes, halfvec quantization, semantic model, live index and generated answers.");
        outputData.put("metricLimitation", "Coverage counts describe known annotations on a weak local precheck only; incomplete labels and hash collisions prohibit semantic or online accuracy conclusions. Scores from this vector-only route must not be used to tune production RRF filtering thresholds.");
        outputData.put("corpusSha256", sha(corpusBytes)); outputData.put("datasetFingerprint", canonicalDigest(cases));
        outputData.put("suiteVersion", suite.path("version").asText()); outputData.put("embeddingDimension", properties.getKnowledge().getEmbeddingDim());
        outputData.put("indexHashCalls", indexed.size()); outputData.put("queryHashCalls", queryCalls);
        outputData.put("outsideSkipped", outsideSkipped); outputData.put("fixedCaseIds", fixedIds);
        outputData.put("filterSettingsSignature", processor.cacheSignature()); outputData.put("caseCount", results.size());
        outputData.put("knownCoverageWithFilter", filteredCoverage); outputData.put("knownCoverageWithoutFilter", rawCoverage);
        outputData.put("rankingJudgeableKnowledgeCases", firstRankingJudgeable);
        outputData.put("unjudgedFilteredHitOccurrences", unjudgedFilteredHits);
        outputData.put("reviewPoolPairs", reviewPool.size()); outputData.put("cases", results);
        writeNew(resultPath, outputData);
        writeNew(output.resolve("unjudged-review-pool.json"), Map.of("executionMode", "LOCAL_HASH_VECTOR_ONLY_PRECHECK",
                "status", "UNREVIEWED", "independentlyAnnotated", false, "proposedLabelsIncluded", false,
                "corpusSha256", sha(corpusBytes), "reviews", reviewPool.values()));
        System.out.println(JSON.writeValueAsString(Map.of("executionMode", "LOCAL_HASH_VECTOR_ONLY_PRECHECK", "qualityMeasurement", false,
                "indexHashCalls", indexed.size(), "queryHashCalls", queryCalls, "outsideSkipped", outsideSkipped,
                "knownCoverageWithFilter", filteredCoverage, "knownCoverageWithoutFilter", rawCoverage,
                "reviewPoolPairs", reviewPool.size())));
    }

    private static List<Ranked> rank(List<Indexed> indexed, float[] query, int limit) {
        return indexed.stream().map(source -> new Ranked(source, cosine(query, source.vector())))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparingInt(item -> item.source().chunk().chunkIndex())).limit(limit).toList();
    }
    private static double cosine(float[] a, float[] b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
    }
    private static RagChainReport.Hit hit(KnowledgeSnippet snippet, PgVectorKnowledgeService.KnowledgeChunk chunk) {
        return new RagChainReport.Hit(snippet.getId(), snippet.getTitle(), snippet.getCategory(), snippet.getScore(), chunk.contentHash(), snippet.getContent());
    }
    private static RagChainReport assess(JsonNode expected, List<KnowledgeSnippet> snippets, Map<Long,Indexed> sources, String query, long elapsed) {
        var hits = snippets.stream().map(s -> hit(s, sources.get(s.getId()).chunk())).toList();
        return RagChainAssessment.assess(expected, List.of(new RagChainReport.Retrieval("local-vector-precheck", query, 5, "SUCCESS", elapsed, hits, null)));
    }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) { var node = JSON.createObjectNode(); var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add); for (var name : names) node.set(name, sorted(value.get(name))); return node; }
        if (value.isArray()) { var node = JSON.createArrayNode(); value.forEach(item -> node.add(sorted(item))); return node; }
        return value;
    }
    private static String canonicalDigest(JsonNode value) throws Exception { return sha(JSON.writeValueAsBytes(sorted(value))); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void writeNew(Path path, Object value) throws Exception {
        try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) { JSON.writerWithDefaultPrettyPrinter().writeValue(out, value); }
    }
}
