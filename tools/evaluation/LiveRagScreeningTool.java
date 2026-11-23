package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RagChainReport;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** Explicit, bounded pilot. No Boot startup, migrations, workers, chat, or reranking. */
public final class LiveRagScreeningTool {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final String SOURCE = "diet_guides.md", VERSION = "v8-longform-200-boundary-v2";
    static final int REQUEST_CAP = 1381, INPUT_CAP = 2_000_000;
    static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    static final Path LEDGER = ROOT.resolve("logs/backend/rag-screening-provider-ledger-20261021-v1.json");
    static final List<String> FIXED = List.of("rag-001","rag-013","rag-016","rag-020","rag-031","rag-045","rag-085","rag-135",
            "rag-049","rag-054","rag-063","rag-076","rag-099","rag-109","rag-120","rag-130",
            "rag-037","rag-060","rag-069","rag-089","rag-098","rag-113","rag-125","rag-143",
            "rag-161","rag-164","rag-178","rag-183","rag-191","rag-197");

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions { }

    public static void main(String[] args) {
        System.setProperty("jdk.httpclient.disableRetryConnect","true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry","false");
        Path output = null;
        try {
            require(args.length >= 4, "ARGUMENTS");
            output = Path.of(args[3]).toAbsolutePath().normalize();
            require(output.startsWith(ROOT.resolve("logs")) && Files.isDirectory(output), "OUTPUT_PATH");
            if ("--self-test".equals(args[0])) { selfTest(output); return; }
            boolean execute = "--execute".equals(args[0]);
            require(execute || "--preflight".equals(args[0]), "MODE");
            var packet = new Packet(Path.of(args[1]), Path.of(args[2]));
            if (execute) {
                require(args.length == 5, "AUTHORIZATION_REQUIRED");
                validateAuthorization(read(Path.of(args[4])), packet.planSha);
            }
            try (var runtime = new Runtime(packet, output, execute)) {
                runtime.run();
            }
        } catch (Throwable error) {
            // Never print arbitrary exception messages, nested JDBC URLs, HTTP bodies, or stack traces.
            String code = error instanceof GateFailure ? error.getMessage() : "EXECUTION_STOPPED_" + error.getClass().getSimpleName();
            try { if (output != null) writeNew(output.resolve("stopped.json"), Map.of("state", "STOPPED", "code", code,
                    "providerRetryCount", 0, "noChatOrRerank", true)); } catch (Exception ignored) { }
            System.err.println(code);
            System.exit(2);
        }
    }

    static class GateFailure extends IllegalStateException { GateFailure(String code) { super(code); } }
    static void require(boolean condition, String code) { if (!condition) throw new GateFailure(code); }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(); }
    }
    static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    static JsonNode read(Path path) throws Exception { return JSON.readTree(Files.readAllBytes(path)); }
    static void writeNew(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static void validateAuthorization(JsonNode auth, String planSha) {
        require(auth.path("approved").isBoolean() && auth.path("approved").asBoolean()
                && planSha.equals(auth.path("planSha256").asText())
                && auth.path("maximumRequests").isIntegralNumber() && auth.path("maximumRequests").asInt() == REQUEST_CAP
                && auth.path("maximumAdmissionUnits").isIntegralNumber() && auth.path("maximumAdmissionUnits").asInt() == INPUT_CAP
                && auth.path("maxRetries").isIntegralNumber() && auth.path("maxRetries").asInt() == 0
                && auth.path("stopOnFirstProviderError").isBoolean() && auth.path("stopOnFirstProviderError").asBoolean()
                && auth.path("chatCallsAllowed").isIntegralNumber() && auth.path("chatCallsAllowed").asInt() == 0
                && auth.path("rerankCallsAllowed").isIntegralNumber() && auth.path("rerankCallsAllowed").asInt() == 0
                && auth.path("supplementalQueryCallsAllowed").isIntegralNumber() && auth.path("supplementalQueryCallsAllowed").asInt() == 0
                && auth.path("preserveAdminEntries").isBoolean() && auth.path("preserveAdminEntries").asBoolean()
                && SOURCE.equals(auth.path("source").asText()) && VERSION.equals(auth.path("sourceVersion").asText())
                && !auth.path("authorizationEvidence").asText().isBlank(), "AUTHORIZATION_SCOPE");
    }

    static final class Packet {
        final JsonNode plan, cases, manifest, expected;
        final String planSha;
        final List<PgVectorKnowledgeService.KnowledgeChunk> chunks;
        final Map<String,Integer> allowedInputs = new HashMap<>();
        Packet(Path prepared, Path manifestPath) throws Exception {
            plan = read(prepared.resolve("plan.json")); var selectedPacket = read(prepared.resolve("screening30.json"));
            var expectedPacket = read(prepared.resolve("expected-chunks.json"));
            require("PREPARED_NOT_EXECUTED".equals(selectedPacket.path("status").asText())
                    && SOURCE.equals(expectedPacket.path("source").asText()) && VERSION.equals(expectedPacket.path("sourceVersion").asText()),"PACKET_WRAPPER");
            cases = selectedPacket.path("cases"); manifest = read(manifestPath); expected = expectedPacket.path("chunks");
            planSha = sha(Files.readAllBytes(prepared.resolve("plan.json")));
            var files = Map.of("corpus", ROOT.resolve("backend/src/main/resources/knowledge/diet_guides.md"),
                    "dataset", ROOT.resolve("backend/src/main/resources/knowledge/rag_eval_cases.json"),
                    "metadata", ROOT.resolve("backend/src/main/resources/knowledge/diet_guides_metadata.json"),
                    "suite", ROOT.resolve("backend/src/main/resources/knowledge/rag_chunking_eval_suite.json"), "manifest", manifestPath);
            for (var file : files.entrySet()) require(sha(Files.readAllBytes(file.getValue())).equals(
                    plan.path("inputFileSha256").path(file.getKey()).asText()), "INPUT_FINGERPRINT");
            require(SOURCE.equals(plan.path("source").asText()) && VERSION.equals(plan.path("sourceVersion").asText())
                    && plan.path("chunkCount").asInt() == 1351 && plan.path("caseCount").asInt() == 30
                    && plan.path("plannedQueryEmbeddingCount").asInt() == 28, "PLAN_SCOPE");
            chunks = new MarkdownKnowledgeChunker(new AppProperties()).split(Files.readString(files.get("corpus")));
            require(chunks.size() == 1351 && manifest.path("chunks").size() == chunks.size() && expected.size() == chunks.size(), "CHUNK_COUNT");
            for (int i=0; i<chunks.size(); i++) {
                var chunk=chunks.get(i); var wanted=manifest.path("chunks").get(i); var exp=expected.get(i);
                require(chunk.chunkKey().equals(wanted.path("chunkKey").asText()) && chunk.title().equals(wanted.path("title").asText())
                        && chunk.content().equals(wanted.path("content").asText()) && chunk.contentHash().equals(wanted.path("contentHash").asText())
                        && chunk.tokenCount()==wanted.path("tokenCount").asInt() && chunk.chunkIndex()==wanted.path("chunkIndex").asInt(-1)
                        && chunk.chunkKey().equals(exp.path("chunkKey").asText()) && chunk.contentHash().equals(exp.path("contentHash").asText())
                        && SOURCE.equals(exp.path("source").asText()) && VERSION.equals(exp.path("sourceVersion").asText()), "ACTUAL_SPLITTER_DRIFT");
                allow(chunk.title().trim()+"\n\n"+chunk.content().trim());
            }
            var dataset=read(files.get("dataset")); var byId=new HashMap<String,JsonNode>();
            dataset.forEach(row->byId.put(row.path("id").asText(),row));
            require(cases.size()==FIXED.size(), "FIXED_CASE_COUNT");
            int queries=0;
            for(int i=0;i<FIXED.size();i++) {
                var row=cases.get(i); String id=FIXED.get(i);
                require(id.equals(row.path("id").asText()) && row.equals(byId.get(id)), "FIXED_CASE_DRIFT");
                RagEvidenceLabels.validate(row,chunks);
                boolean outside="OUT_OF_SCOPE".equals(row.path("answerability").asText());
                require(outside==row.path("expectNoAnswer").asBoolean(),"OUTSIDE_RULE_DRIFT");
                if(!outside) { allow(row.path("question").asText()); queries++; }
            }
            require(queries==28,"QUERY_COUNT");
        }
        void allow(String text) { allowedInputs.merge(sha(text),1,Integer::sum); }
    }

    static StandardEnvironment environment() throws Exception {
        var env=new StandardEnvironment(); var dotenv=new HashMap<String,Object>();
        Path file=ROOT.resolve(".env");
        if(Files.exists(file)) for(String raw:Files.readAllLines(file)) {
            String line=raw.trim(); if(line.isEmpty()||line.startsWith("#"))continue;
            if(line.startsWith("export "))line=line.substring(7);
            int at=line.indexOf('='); if(at<1)continue;
            String value=line.substring(at+1).trim();
            if(value.length()>1 && ((value.startsWith("\"")&&value.endsWith("\"")) || (value.startsWith("'")&&value.endsWith("'"))))value=value.substring(1,value.length()-1);
            dotenv.put(line.substring(0,at).trim(),value);
        }
        env.getPropertySources().addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("pilotDotenv",dotenv));
        var yaml=new YamlPropertySourceLoader().load("application",new FileSystemResource(ROOT.resolve("backend/src/main/resources/application.yml")));
        yaml.forEach(env.getPropertySources()::addLast);
        return env;
    }

    static void validateJdbc(String jdbc) {
        require(jdbc!=null && jdbc.startsWith("jdbc:postgresql://"),"JDBC_ALLOWLIST");
        try {
            var uri=URI.create(jdbc.substring(5));
            require(Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost())
                    && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null
                    && uri.getPath()!=null && uri.getPath().matches("/[a-zA-Z0-9_-]+"),"JDBC_ALLOWLIST");
        } catch(IllegalArgumentException invalid) { throw new GateFailure("JDBC_ALLOWLIST"); }
    }
    static URI validateEndpoint(String baseUrl) {
        var endpoint=AiEndpointResolver.embeddings(baseUrl);
        String host=endpoint.getHost();
        require("https".equals(endpoint.getScheme()) && host!=null && endpoint.getRawUserInfo()==null
                && endpoint.getRawQuery()==null && endpoint.getRawFragment()==null && (endpoint.getPort()==-1||endpoint.getPort()==443)
                && (Set.of("dashscope.aliyuncs.com","dashscope-intl.aliyuncs.com","dashscope-us.aliyuncs.com").contains(host)
                    || host.endsWith(".maas.aliyuncs.com")),"PROVIDER_ALLOWLIST");
        return endpoint;
    }

    static final class Runtime implements AutoCloseable {
        final Packet packet; final Path output; final boolean execute;
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final AppProperties props; final JdbcTemplate jdbc; final TransactionTemplate readonly;
        final KnowledgeEmbeddingService embedding; final PgVectorKnowledgeService vectors;
        final SingleTransport transport; BudgetLedger ledger;
        Runtime(Packet packet,Path output,boolean execute) throws Exception {
            this.packet=packet;this.output=output;this.execute=execute;
            var env=environment(); props=Binder.get(env).bind("mealcheck",Bindable.of(AppProperties.class)).orElseGet(AppProperties::new);
            require(props.getKnowledge().getChunkMinTokens()==300 && props.getKnowledge().getChunkMaxTokens()==500
                    && props.getKnowledge().getChunkOverlapTokens()==50
                    && "recursive-markdown".equals(props.getKnowledge().getSplitterStrategy()),"DEFAULT_SPLITTER_CONFIG_REQUIRED");
            String url=env.getProperty("spring.datasource.url");validateJdbc(url);
            var ds=new DriverManagerDataSource(url,env.getProperty("spring.datasource.username"),env.getProperty("spring.datasource.password"));
            ds.setDriverClassName("org.postgresql.Driver");
            jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(10);
            var manager=new DataSourceTransactionManager(ds);readonly=new TransactionTemplate(manager);readonly.setReadOnly(true);readonly.setTimeout(15);
            context.setEnvironment(env);context.register(Transactions.class);
            context.registerBean(AppProperties.class,()->props);context.registerBean(DataSource.class,()->ds);
            context.registerBean(JdbcTemplate.class,()->jdbc);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);
            context.registerBean(VectorCompressionService.class,()->new VectorCompressionService(props));
            context.registerBean(KnowledgeVectorPersistenceService.class,()->new KnowledgeVectorPersistenceService(jdbc,context.getBean(VectorCompressionService.class)));
            context.registerBean(KnowledgeSourceSyncStore.class,()->new KnowledgeSourceSyncStore(jdbc,context.getBean(KnowledgeVectorPersistenceService.class),env.getProperty("mealcheck.knowledge.source-sync-lease-seconds",Integer.class,120)));
            context.registerBean(KnowledgeEmbeddingSettingsStore.class,()->new KnowledgeEmbeddingSettingsStore(jdbc));
            context.registerBean(KnowledgeEmbeddingProfileStore.class,()->new KnowledgeEmbeddingProfileStore(jdbc,props,env.getProperty("mealcheck.ai-settings.encryption-key","")));
            var obs=new ApplicationObservability(new SimpleMeterRegistry());
            transport=new SingleTransport(props,obs,packet.allowedInputs);
            context.registerBean(AiHttpTransport.class,()->transport);
            context.registerBean(KnowledgeEmbeddingService.class,()->new KnowledgeEmbeddingService(props,JSON,new HashEmbeddingService(props),obs,transport));
            context.registerBean(MarkdownKnowledgeChunker.class,()->new MarkdownKnowledgeChunker(props));
            context.registerBean(PgVectorKnowledgeService.class,()->new PgVectorKnowledgeService(jdbc,context.getBean(KnowledgeEmbeddingService.class),props,
                    context.getBean(KnowledgeVectorPersistenceService.class),context.getBean(MarkdownKnowledgeChunker.class),context.getBean(VectorCompressionService.class),context.getBean(KnowledgeSourceSyncStore.class)));
            context.refresh();embedding=context.getBean(KnowledgeEmbeddingService.class);vectors=context.getBean(PgVectorKnowledgeService.class);
            require(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(KnowledgeSourceSyncStore.class))
                    && org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(KnowledgeVectorPersistenceService.class)),"TRANSACTION_PROXY_MISSING");
        }
        void run() throws Exception {
            var before=readonly.execute(status->snapshot()); writeNew(output.resolve("before-index.json"),before);
            var info=readonly.execute(status->configuration()); writeNew(output.resolve("preflight.json"),info);
            require(Boolean.TRUE.equals(info.get("storageMatchesConfiguration")),"STORAGE_PROFILE_MISMATCH");
            require(!Boolean.TRUE.equals(info.get("hasStaleEmbeddings")),"MIXED_OR_STALE_SIGNATURES");
            require("SEMANTIC".equals(info.get("activeMode")),"SEMANTIC_MODE_REQUIRED_NO_AUTOMATIC_SWITCH");
            require(embedding.semanticProfile().configured(),"PROFILE_NOT_CONFIGURED");
            URI endpoint=validateEndpoint(embedding.semanticProfile().baseUrl());
            String signature=vectors.configuredSignature();
            if(!execute)return;
            ledger=new BudgetLedger(LEDGER,packet.planSha,signature);
            try {
                transport.arm(ledger,endpoint,embedding.semanticProfile());
                var sync=vectors.syncSource(SOURCE,VERSION,AppTime.now(),packet.chunks,true,"recursive-markdown");
                writeNew(output.resolve("sync.json"),sync);
                var after=readonly.execute(status->snapshot());writeNew(output.resolve("after-sync-index.json"),after);
                validateIndex(before,after,packet.chunks,signature);
                require(signature.equals(vectors.configuredSignature()) && vectors.storageMatchesConfiguration() && !vectors.hasStaleEmbeddings(),"PROFILE_DRIFT");
                screen();
                var end=readonly.execute(status->snapshot());writeNew(output.resolve("after-screening-index.json"),end);
                validateIndex(before,end,packet.chunks,signature);
                require(signature.equals(vectors.configuredSignature()) && vectors.storageMatchesConfiguration() && !vectors.hasStaleEmbeddings(),"PROFILE_DRIFT");
                ledger.complete();writeNew(output.resolve("budget-summary.json"),ledger.state);
            } catch(Throwable error) {
                ledger.stop(error instanceof GateFailure ? error.getMessage():"EXECUTION_STOPPED_"+error.getClass().getSimpleName());
                writeNew(output.resolve("budget-summary.json"),ledger.state);throw error;
            }
        }
        List<Map<String,Object>> snapshot() {
            // SELECT deliberately excludes all vector values, profile secrets, and administrator bodies.
            return jdbc.query("SELECT id,source,title,content_hash,chunk_key,source_version,embedding_signature,embedding IS NOT NULL AS present,CASE WHEN source='diet_guides.md' THEN content ELSE NULL END AS system_content FROM knowledge_chunks ORDER BY source,chunk_key,id",(rs,n)->{
                var row=new LinkedHashMap<String,Object>();row.put("id",rs.getLong("id"));row.put("source",rs.getString("source"));row.put("title",rs.getString("title"));
                row.put("contentHash",rs.getString("content_hash"));row.put("chunkKey",rs.getString("chunk_key"));row.put("sourceVersion",rs.getString("source_version"));
                row.put("signature",rs.getString("embedding_signature"));row.put("hasEmbedding",rs.getBoolean("present"));
                if(SOURCE.equals(rs.getString("source")))row.put("content",rs.getString("system_content"));return row;
            });
        }
        Map<String,Object> configuration() {
            // Fail before dispatch on any missing current schema fields. Never migrate implicitly.
            jdbc.queryForList("SELECT id,mode,active_profile_id,draft_profile_id FROM knowledge_embedding_settings WHERE id=1");
            jdbc.queryForList("SELECT id,model,dimension,send_dimensions FROM knowledge_embedding_profiles WHERE false");
            jdbc.queryForList("SELECT source,splitter_strategy,embedding_signature,data_revision FROM knowledge_source_sync WHERE false");
            jdbc.queryForList("SELECT embedding_input_tokens,embedding_total_tokens FROM knowledge_chunks WHERE false");
            var profile=embedding.semanticProfile();var stored=context.getBean(KnowledgeEmbeddingProfileStore.class).active();
            var result=new LinkedHashMap<String,Object>();result.put("state","READ_ONLY_PREFLIGHT");result.put("activeMode",embedding.activeMode());
            result.put("activeProfileId",stored==null?null:stored.id());result.put("model",profile.model());
            result.put("configuredProviderHost",profile.baseUrl()==null?null:URI.create(profile.baseUrl()).getHost());
            result.put("credentialConfigured",profile.configured());result.put("dimension",embedding.activeDimension());
            result.put("storage",props.getKnowledge().getVectorStorage());result.put("signature",vectors.configuredSignature());
            result.put("storageMatchesConfiguration",vectors.storageMatchesConfiguration());result.put("hasStaleEmbeddings",vectors.hasStaleEmbeddings());
            result.put("planSha256",packet.planSha);result.put("providerCalls",0);result.put("sourceSync",vectors.sourceSyncStatus());
            result.put("retrievalSignature",vectors.retrievalSignature());result.put("rerankCallsAllowed",0);
            result.put("resultFilterEnabled",props.getKnowledge().isResultFilterEnabled());result.put("resultMinScore",props.getKnowledge().getResultMinScore());
            result.put("resultRelativeScore",props.getKnowledge().getResultRelativeScore());result.put("resultDedupSimilarity",props.getKnowledge().getResultDedupSimilarity());
            result.put("candidateMultiplier",new KnowledgeResultPostProcessor(props).candidateMultiplier());return result;
        }
        void screen() throws Exception {
            var processor=new KnowledgeResultPostProcessor(props);var rows=new ArrayList<Map<String,Object>>();var pool=new ArrayList<Map<String,Object>>();
            int searched=0,skipped=0;
            for(var expected:packet.cases) {
                var row=new LinkedHashMap<String,Object>();String id=expected.path("id").asText(), query=expected.path("question").asText();
                row.put("caseId",id);row.put("question",query);row.put("answerability",expected.path("answerability").asText());
                if(expected.path("expectNoAnswer").asBoolean()) {
                    skipped++;row.put("state","SKIPPED_OUT_OF_SCOPE");row.put("queryEmbeddingCalls",0);row.put("rawCandidates",List.of());
                    row.put("assessment",RagChainAssessment.assess(expected,List.of()));rows.add(row);continue;
                }
                require(vectors.storageMatchesConfiguration() && !vectors.hasStaleEmbeddings(),"VECTOR_RETRIEVAL_REQUIRED");
                int initialCalls=ledger.count();long started=System.nanoTime();
                var raw=vectors.search(query,Math.min(20,5*processor.candidateMultiplier()));searched++;
                require(ledger.count()-initialCalls==1,"EXACT_ONE_ORIGINAL_QUERY_EMBEDDING");
                var processed=processor.process(raw,5);var all=processor.process(raw,raw.size());
                var topIds=new HashSet<Long>();processed.snippets().forEach(s->topIds.add(s.getId()));
                var retained=new HashSet<Long>();all.snippets().forEach(s->retained.add(s.getId()));
                var candidates=new ArrayList<Map<String,Object>>();int rank=0;
                for(var snippet:raw) {
                    var hit=hit(snippet);var candidate=new LinkedHashMap<String,Object>();
                    candidate.put("rank",++rank);candidate.put("hit",hit);candidate.put("judgment",RagEvidenceLabels.judgment(hit,expected));
                    candidate.put("disposition",topIds.contains(snippet.getId())?"RETAINED_TOP5":!Double.isFinite(snippet.getScore())||snippet.getScore()+1e-9<processed.effectiveThreshold()?"THRESHOLD_FILTERED":!retained.contains(snippet.getId())?"DUPLICATE_FILTERED":"RESULT_LIMIT");
                    candidates.add(candidate);
                    if("UNJUDGED".equals(candidate.get("judgment"))) {
                        var review=new LinkedHashMap<String,Object>();review.put("caseId",id);review.put("question",query);review.putAll(candidate);pool.add(review);
                    }
                }
                long elapsed=(System.nanoTime()-started)/1_000_000;
                var retrieval=new RagChainReport.Retrieval("screening-"+id,query,5,"SUCCESS",elapsed,processed.snippets().stream().map(LiveRagScreeningTool::hit).toList(),null);
                var rawRetrieval=new RagChainReport.Retrieval("raw-"+id,query,5,"SUCCESS",elapsed,raw.stream().limit(5).map(LiveRagScreeningTool::hit).toList(),null);
                row.put("state","RETRIEVED_PRODUCTION_PGVECTOR_NO_RERANK");row.put("queryEmbeddingCalls",1);row.put("rawCandidates",candidates);
                row.put("postProcessor",processed);row.put("capturedRetrieval",retrieval);row.put("assessment",RagChainAssessment.assess(expected,List.of(retrieval)));
                row.put("rawTop5Assessment",RagChainAssessment.assess(expected,List.of(rawRetrieval)));rows.add(row);
                writeNew(output.resolve("case-"+id+".json"),row);
            }
            require(searched==28 && skipped==2,"SCREENING_COUNTS");
            var results=new LinkedHashMap<String,Object>();
            results.put("executionMode","LIVE_PRODUCTION_PGVECTOR_RETRIEVAL_SCREENING");results.put("caseCount",30);
            results.put("queryEmbeddingCalls",searched);results.put("outsideSkipped",skipped);results.put("chatCalls",0);results.put("rerankCalls",0);results.put("maxRetries",0);
            results.put("independentlyAnnotated",false);results.put("routingOrToolSelectionMeasured",false);results.put("answerGenerationMeasured",false);
            results.put("outsideSkipPolicy","FIXED_SUITE_CONTRACT_NOT_MODEL_ROUTING");results.put("results",rows);
            writeNew(output.resolve("results.json"),results);
            writeNew(output.resolve("unjudged-review-pool.json"),pool);
        }
        public void close() throws Exception {try {context.close();}finally {if(ledger!=null)ledger.close();} }
    }

    static RagChainReport.Hit hit(KnowledgeSnippet s) {
        return new RagChainReport.Hit(s.getId(),s.getTitle(),s.getCategory(),s.getScore(),RagEvidenceLabels.contentId(s.getTitle(),s.getContent()),s.getContent());
    }
    static void validateIndex(List<Map<String,Object>> before,List<Map<String,Object>> after,List<PgVectorKnowledgeService.KnowledgeChunk> chunks,String signature) {
        var preservedBefore=before.stream().filter(r->!SOURCE.equals(r.get("source"))).toList();
        var preservedAfter=after.stream().filter(r->!SOURCE.equals(r.get("source"))).toList();
        require(preservedBefore.equals(preservedAfter),"ADMIN_OR_OTHER_SOURCE_CHANGED");
        var wanted=new HashMap<String,PgVectorKnowledgeService.KnowledgeChunk>();chunks.forEach(c->wanted.put(c.chunkKey(),c));int count=0;
        for(var row:after) if(SOURCE.equals(row.get("source"))) {
            count++;var chunk=wanted.remove((String)row.get("chunkKey"));
            require(chunk!=null && chunk.title().equals(row.get("title")) && chunk.content().equals(row.get("content"))
                    && chunk.contentHash().equals(row.get("contentHash")) && VERSION.equals(row.get("sourceVersion"))
                    && signature.equals(row.get("signature")) && Boolean.TRUE.equals(row.get("hasEmbedding")),"SYSTEM_INDEX_IDENTITY_MISMATCH");
        }
        require(count==1351 && wanted.isEmpty(),"SYSTEM_INDEX_COUNT");
    }

    /** A single locked journal shared across all runs. An interrupted dispatch is terminal. */
    static final class BudgetLedger implements AutoCloseable {
        final Path path;final FileChannel lockChannel;final FileLock lock;final Map<String,Object> state=new LinkedHashMap<>();
        final List<Map<String,Object>> attempts=new ArrayList<>();
        BudgetLedger(Path path,String planSha,String signature) throws Exception {
            this.path=path;Files.createDirectories(path.getParent());
            lockChannel=FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=lockChannel.tryLock();require(lock!=null,"LEDGER_ALREADY_RUNNING");
            if(Files.exists(path)) {close();throw new GateFailure("PRIOR_LEDGER_EXISTS_REVIEW_REQUIRED_NO_AUTOMATIC_RESTART");}
            state.put("planSha256",planSha);state.put("signature",signature);state.put("status","RUNNING");
            state.put("requestCap",REQUEST_CAP);state.put("admissionCap",INPUT_CAP);state.put("reservedAdmissionUnits",0L);state.put("attempts",attempts);persist();
        }
        int count() {return attempts.size();}
        void reserve(String inputHash,int utf8Bytes) throws Exception {
            require("RUNNING".equals(state.get("status")),"LEDGER_STOPPED");
            long reserved=((Number)state.get("reservedAdmissionUnits")).longValue(), units=(long)utf8Bytes+128;
            if(count()>=REQUEST_CAP || reserved+units>INPUT_CAP) {stop("BUDGET_EXHAUSTED");throw new GateFailure("BUDGET_EXHAUSTED");}
            var attempt=new LinkedHashMap<String,Object>();attempt.put("ordinal",count()+1);attempt.put("inputSha256",inputHash);
            attempt.put("utf8Bytes",utf8Bytes);attempt.put("upperReservationUnits",units);attempt.put("state","RESERVED_BEFORE_DISPATCH");
            attempts.add(attempt);state.put("reservedAdmissionUnits",reserved+units);persist();
        }
        void response(int status,JsonNode response) throws Exception {
            var attempt=attempts.get(attempts.size()-1);attempt.put("httpStatus",status);attempt.put("state","RESPONSE_RECEIVED");
            // Usage numbers only; raw response, URLs, credentials, and provider free-text are never persisted.
            var usage=response.path("usage");var usageOut=new LinkedHashMap<String,Long>();
            for(String field:List.of("prompt_tokens","input_tokens","total_tokens"))if(usage.path(field).isIntegralNumber())usageOut.put(field,usage.path(field).asLong());
            attempt.put("usage",usageOut);
            String providerCode=response.path("error").path("code").asText(response.path("code").asText(""));
            if(providerCode.matches("[A-Za-z0-9_.-]{1,100}"))attempt.put("providerErrorCode",providerCode);
            persist();
        }
        void stop(String code) throws Exception {
            state.put("status","STOPPED");if(!state.containsKey("stopCode"))state.put("stopCode",code);persist();
        }
        void complete() throws Exception {state.put("status","COMPLETE");persist();}
        void persist() throws Exception {
            Path tmp=path.resolveSibling(path.getFileName()+".tmp");
            byte[] bytes=(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state)+"\n").getBytes(StandardCharsets.UTF_8);
            try(var file=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())file.write(data);file.force(true);
            }
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        public void close() throws Exception {if(lock!=null&&lock.isValid())lock.release();lockChannel.close();}
    }

    /** Deliberate bypass of application retries/budget DB, replaced by the above local journal. */
    static final class SingleTransport extends AiHttpTransport {
        final Map<String,Integer> allowed; BudgetLedger ledger;URI endpoint;String model;int dimension;boolean sendDimensions;
        final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build();
        final Sender sender;
        long lastDispatch;
        @FunctionalInterface interface Sender {HttpResponse<String> send(HttpRequest request) throws Exception;}
        SingleTransport(AppProperties props,ApplicationObservability obs,Map<String,Integer> allowed) {
            this(props,obs,allowed,null);
        }
        SingleTransport(AppProperties props,ApplicationObservability obs,Map<String,Integer> allowed,Sender sender) {
            super(null,null,null,props,JSON,obs,null);this.allowed=new HashMap<>(allowed);
            this.sender=sender==null?request->client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)):sender;
        }
        void arm(BudgetLedger ledger,URI endpoint,KnowledgeEmbeddingProfileStore.Profile profile) {
            this.ledger=ledger;this.endpoint=endpoint;this.model=profile.model();this.dimension=profile.dimension();this.sendDimensions=profile.sendDimensions();
        }
        @Override public HttpResponse<String> send(String name,HttpRequest request,String body) {return send(name,name,request,body);}
        @Override public HttpResponse<String> send(String operation,String name,HttpRequest request,String body) {
            try {
                require(ledger!=null,"PREFLIGHT_FORBIDS_PROVIDER_CALLS");
                require("embedding".equals(operation) && endpoint.equals(request.uri()) && "POST".equals(request.method()),"PROVIDER_OPERATION_ALLOWLIST");
                var payload=JSON.readTree(body);var input=payload.path("input");
                require(input.isTextual() && model.equals(payload.path("model").asText()) && (sendDimensions
                        ?payload.path("dimensions").isIntegralNumber()&&payload.path("dimensions").asInt()==dimension:!payload.has("dimensions")),"PROVIDER_PAYLOAD_ALLOWLIST");
                var keys=new HashSet<String>();payload.fieldNames().forEachRemaining(keys::add);
                require(keys.equals(sendDimensions?Set.of("model","input","dimensions"):Set.of("model","input")),"PROVIDER_PAYLOAD_ALLOWLIST");
                String hash=sha(input.asText());int remaining=allowed.getOrDefault(hash,0);require(remaining>0,"INPUT_NOT_IN_APPROVED_PLAN");
                long delay=400_000_000L-(System.nanoTime()-lastDispatch);if(delay>0)Thread.sleep(delay/1_000_000,(int)(delay%1_000_000));
                ledger.reserve(hash,input.asText().getBytes(StandardCharsets.UTF_8).length);allowed.put(hash,remaining-1);lastDispatch=System.nanoTime();
                HttpResponse<String> response=sender.send(request);
                JsonNode parsed;boolean validJson=true;
                try {parsed=JSON.readTree(response.body());}catch(Exception badJson){parsed=JSON.createObjectNode();validJson=false;}
                if(parsed==null){parsed=JSON.createObjectNode();validJson=false;}
                ledger.response(response.statusCode(),parsed);
                if(response.statusCode()<200 || response.statusCode()>=300) {ledger.stop("PROVIDER_HTTP_"+response.statusCode());throw new GateFailure("PROVIDER_HTTP_"+response.statusCode());}
                require(validJson && parsed.isObject(),"PROVIDER_INVALID_JSON");
                var vector=parsed.path("data").path(0).path("embedding");require(vector.isArray()&&vector.size()==dimension,"PROVIDER_VECTOR_DIMENSION");
                for(var number:vector)require(number.isNumber()&&Double.isFinite(number.asDouble())&&Float.isFinite((float)number.asDouble()),"PROVIDER_VECTOR_INVALID");
                return response;
            }catch(Throwable failure) {
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                String code=failure instanceof GateFailure?failure.getMessage():"PROVIDER_TRANSPORT_STOPPED";
                try {if(ledger!=null)ledger.stop(code);}catch(Exception persistenceFailure) {throw new GateFailure("LEDGER_WRITE_FAILED_STOP");}
                throw new GateFailure(code);
            }
        }
    }

    static final class FakeResponse implements HttpResponse<String> {
        final int status;final String body;
        FakeResponse(int status,String body){this.status=status;this.body=body;}
        public int statusCode(){return status;}public String body(){return body;}
        public HttpRequest request(){return HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")).build();}
        public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
        public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(k,v)->true);}
        public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}
        public URI uri(){return request().uri();}public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }

    /** Exercises the production embedding parser through a counting, non-network sender. */
    static int mockProductionEmbedding(Path output,String label,int status,String body,String wantedStop) throws Exception {
        var props=new AppProperties();props.getKnowledge().setEmbeddingDim(2);
        props.getKnowledge().setEmbeddingApiKey("offline-fixture-credential");
        props.getKnowledge().setEmbeddingBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        props.getKnowledge().setEmbeddingModel("offline-fixture-model");
        var obs=new ApplicationObservability(new SimpleMeterRegistry());var calls=new java.util.concurrent.atomic.AtomicInteger();
        String input="离线门禁";
        try(var ledger=new BudgetLedger(output.resolve("mock-"+label+"-budget.json"),"test-plan","test-signature")) {
            var transport=new SingleTransport(props,obs,Map.of(sha(input),1),request->{
                require(read(ledger.path).path("attempts").size()==1,"MOCK_MUST_RESERVE_BEFORE_DISPATCH");
                require(!read(ledger.path).path("attempts").get(0).has("httpStatus"),"MOCK_RESERVATION_BEFORE_RESPONSE");
                calls.incrementAndGet();return new FakeResponse(status,body);
            });
            var service=new KnowledgeEmbeddingService(props,JSON,new HashEmbeddingService(props),obs,transport);
            transport.arm(ledger,validateEndpoint(props.getKnowledge().getEmbeddingBaseUrl()),service.semanticProfile());
            boolean failed=false;
            try {var result=service.embedForModeWithUsage(input,"SEMANTIC");
                require(result.vector().length==2 && result.inputTokens()==7L && result.totalTokens()==7L,"MOCK_SUCCESS_PARSER");
            }catch(IllegalStateException expected){failed=true;}
            require(calls.get()==1 && ledger.count()==1,"MOCK_EXACT_SINGLE_DISPATCH");
            if(wantedStop==null) {
                require(!failed && "RUNNING".equals(ledger.state.get("status")),"MOCK_SUCCESS_STATE");
                // Approved inputs are single-use, even after a successful first response.
                try {service.embedForMode(input,"SEMANTIC");throw new AssertionError();}catch(IllegalStateException expected){}
                require(calls.get()==1 && "INPUT_NOT_IN_APPROVED_PLAN".equals(ledger.state.get("stopCode")),"MOCK_INPUT_SINGLE_USE");
            } else {
                require(failed && wantedStop.equals(ledger.state.get("stopCode")),"MOCK_STOP_REASON");
                ledger.stop("OUTER_WRAPPER_FAILURE");require(wantedStop.equals(ledger.state.get("stopCode")),"MOCK_FIRST_STOP_PRESERVED");
                try {service.embedForMode(input,"SEMANTIC");throw new AssertionError();}catch(IllegalStateException expected){}
                require(calls.get()==1,"MOCK_STOP_NO_SECOND_DISPATCH");
            }
            String persisted=Files.readString(ledger.path);
            require(!persisted.contains("offline-fixture-credential") && !persisted.contains("SECRET_NEVER_PERSIST"),"MOCK_RESPONSE_SECRETS_EXCLUDED");
            if(status==403 && body.contains("AccessDenied"))require(persisted.contains("AccessDenied"),"MOCK_SAFE_PROVIDER_CODE");
        }
        return 8;
    }

    static void selfTest(Path output) throws Exception {
        int assertions=0;
        validateJdbc("jdbc:postgresql://localhost:5432/mealcheck");assertions++;
        for(String invalid:List.of("jdbc:postgresql://evil.example/mealcheck","jdbc:postgresql://localhost/mealcheck?socketFactory=evil","jdbc:postgresql:mealcheck")) {
            try{validateJdbc(invalid);throw new AssertionError();}catch(GateFailure expected){assertions++;}
        }
        validateEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1");assertions++;
        for(String invalid:List.of("http://dashscope.aliyuncs.com/v1","https://dashscope.aliyuncs.com.evil.example/v1","https://user:pass@dashscope.aliyuncs.com/v1","https://127.0.0.1/v1")) {
            try{validateEndpoint(invalid);throw new AssertionError();}catch(GateFailure expected){assertions++;}
        }
        Path testLedger=output.resolve("offline-budget.json");
        try(var ledger=new BudgetLedger(testLedger,"test-plan","test-signature")) {
            ledger.reserve(sha("测试"),"测试".getBytes(StandardCharsets.UTF_8).length);
            require(ledger.count()==1 && ((Number)ledger.state.get("reservedAdmissionUnits")).intValue()==134,"SELFTEST_RESERVATION");assertions++;
            require(read(testLedger).path("attempts").get(0).path("state").asText().equals("RESERVED_BEFORE_DISPATCH"),"SELFTEST_PERSISTENCE");assertions++;
            ledger.stop("PROVIDER_HTTP_403");
            try{ledger.reserve(sha("next"),4);throw new AssertionError();}catch(GateFailure expected){assertions++;}
        }
        try{new BudgetLedger(testLedger,"test-plan","test-signature");throw new AssertionError();}catch(GateFailure expected){assertions++;}
        Path capLedger=output.resolve("offline-cap-budget.json");
        try(var ledger=new BudgetLedger(capLedger,"test-plan","test-signature")) {
            try{ledger.reserve(sha("cap"),INPUT_CAP);throw new AssertionError();}catch(GateFailure expected){assertions++;}
            require(ledger.count()==0 && "STOPPED".equals(ledger.state.get("status")),"SELFTEST_CAP_BEFORE_DISPATCH");assertions++;
        }
        try(var ledger=new BudgetLedger(output.resolve("offline-request-cap.json"),"test-plan","test-signature")) {
            for(int i=0;i<REQUEST_CAP;i++)ledger.attempts.add(new LinkedHashMap<>());
            try{ledger.reserve(sha("cap"),1);throw new AssertionError();}catch(GateFailure expected){assertions++;}
            require(ledger.count()==REQUEST_CAP && "STOPPED".equals(ledger.state.get("status")),"SELFTEST_REQUEST_CAP");assertions++;
        }
        var props=new AppProperties();var obs=new ApplicationObservability(new SimpleMeterRegistry());
        var transport=new SingleTransport(props,obs,Map.of(sha("allowed"),1));
        try{transport.send("embedding",HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),"{}");throw new AssertionError();}
        catch(GateFailure expected){require("PREFLIGHT_FORBIDS_PROVIDER_CALLS".equals(expected.getMessage()),"SELFTEST_NO_NETWORK");assertions++;}
        assertions+=mockProductionEmbedding(output,"success",200,"{\"data\":[{\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}",null);
        for(int status:List.of(400,401,403,429,500,503))assertions+=mockProductionEmbedding(output,"http-"+status,status,
                "{\"error\":{\"code\":\"AccessDenied\",\"message\":\"SECRET_NEVER_PERSIST\"}}","PROVIDER_HTTP_"+status);
        assertions+=mockProductionEmbedding(output,"bad-dimension",200,"{\"data\":[{\"embedding\":[0.1]}]}","PROVIDER_VECTOR_DIMENSION");
        assertions+=mockProductionEmbedding(output,"bad-number",200,"{\"data\":[{\"embedding\":[1e100,0.2]}]}","PROVIDER_VECTOR_INVALID");
        assertions+=mockProductionEmbedding(output,"bad-json",200,"SECRET_NEVER_PERSIST","PROVIDER_INVALID_JSON");
        assertions+=mockProductionEmbedding(output,"html-403",403,"SECRET_NEVER_PERSIST","PROVIDER_HTTP_403");
        writeNew(output.resolve("self-test.json"),Map.of("state","OFFLINE_GATES_PASSED","assertions",assertions,"databaseConnections",0,"providerCalls",0));
    }
}
