package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false","mealcheck.knowledge.auto-index=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(AppProperties.class)
@Import({KnowledgeRebuildStore.class,KnowledgeSourceSyncStore.class,KnowledgeVectorPersistenceService.class,VectorCompressionService.class})
@Testcontainers
class KnowledgeEmbeddingModeDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired KnowledgeRebuildStore store;
    @Autowired KnowledgeSourceSyncStore sync;
    @Autowired KnowledgeVectorPersistenceService persistence;
    @Autowired VectorCompressionService compression;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @org.junit.jupiter.api.BeforeEach void resetKnowledge() {
        jdbc.execute("TRUNCATE knowledge_embedding_rebuild_jobs,knowledge_chunks CASCADE");
        jdbc.update("UPDATE knowledge_embedding_settings SET mode=NULL,signature=NULL WHERE id=1");
        jdbc.execute("TRUNCATE knowledge_source_sync");
    }

    @Test @SuppressWarnings("unchecked")
    void changedEndpointDuringAnEmbeddingStopsTheWorkerAndCannotPublishTheOldTask() throws Exception {
        properties.getKnowledge().setEmbeddingApiKey("fixture-key");
        properties.getKnowledge().setEmbeddingBaseUrl("https://old.embedding.test/v1");
        properties.getKnowledge().setEmbeddingModel(KnowledgeEmbeddingService.QWEN_FLASH);
        var mapper=new ObjectMapper();
        var metrics=mock(ApplicationObservability.class);
        var transport=mock(AiHttpTransport.class);
        var calls=new AtomicInteger();
        HttpResponse<String> response=mock(HttpResponse.class);
        var vector=new float[512];vector[0]=1;
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":"+mapper.writeValueAsString(vector)+"}]}");
        when(transport.send(any(),any(),any())).thenAnswer(invocation->{
            calls.incrementAndGet();
            properties.getKnowledge().setEmbeddingBaseUrl("https://new.embedding.test/v1");
            return response;
        });
        var embeddings=new KnowledgeEmbeddingService(properties,mapper,new HashEmbeddingService(properties),metrics,transport);
        embeddings.setSettings(new KnowledgeEmbeddingSettingsStore(jdbc));
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature='hash-v1:512:halfvec-v1' WHERE id=1");
        var chunker=new MarkdownKnowledgeChunker(properties);
        var vectors=new PgVectorKnowledgeService(jdbc,embeddings,properties,persistence,chunker,compression,sync);
        var index=new KnowledgeIndexService(vectors,properties,mock(RedisCacheService.class),mock(RagEvaluationService.class),chunker,metrics,mock(KnowledgeRerankerService.class),new KnowledgeResultPostProcessor(properties));
        var worker=new KnowledgeMaintenanceService(store,embeddings,vectors,index,properties,Runnable::run,new SimpleMeterRegistry());
        var lifecycle=new KnowledgeJobLifecycleService(store,embeddings,compression,properties);
        worker.setLifecycle(lifecycle);
        long id=store.requestBuild(lifecycle.signature("SEMANTIC"),512,"halfvec","SEMANTIC");
        worker.processBatch();
        assertThat(store.status(id).get("status")).isEqualTo("INVALIDATED");
        assertThat(store.status(id).get("last_error")).isEqualTo("CONFIG_CHANGED");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(embeddings.activeMode()).isEqualTo("LOCAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE embedding_signature LIKE 'semantic-v1:%'",Long.class)).isZero();
        assertThatThrownBy(() -> lifecycle.resubmit(id,"SEMANTIC")).hasMessageContaining("预览");
        var profiles=new KnowledgeEmbeddingProfileStore(jdbc,properties,"fixture-secret");
        var previews=new KnowledgeRebuildPreviewService(jdbc,chunker,embeddings,compression,index,store,properties,mapper,profiles);
        lifecycle.setPreviews(previews);
        String token=(String)previews.preview("SEMANTIC",null,null).get("previewToken");
        var replacement=new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> lifecycle.resubmit(id,"SEMANTIC",null,token,"admin"));
        assertThat(replacement).isNotEqualTo(id);
    }

    @Test @SuppressWarnings("unchecked")
    void completePipelinePreservesManualKnowledgeAndOldProviderUntilAllTargetVectorsAreReady() throws Exception {
        var mapper=new ObjectMapper();
        var observability=mock(ApplicationObservability.class);
        var transport=mock(AiHttpTransport.class);
        var calls=new AtomicInteger();
        var failNext=new AtomicBoolean();
        properties.getKnowledge().setEmbeddingApiKey("fixture-key");
        properties.getKnowledge().setEmbeddingBaseUrl("https://embedding.test/v1");
        properties.getKnowledge().setEmbeddingModel(KnowledgeEmbeddingService.QWEN_FLASH);
        HttpResponse<String> response=mock(HttpResponse.class);
        var vector=new float[512]; vector[0]=1;
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"embedding\":"+mapper.writeValueAsString(vector)+"}]}");
        when(transport.send(any(),any(),any())).thenAnswer(call -> {
            calls.incrementAndGet();
            if (failNext.getAndSet(false)) throw new IllegalStateException("fixture unavailable");
            return response;
        });
        var embeddings=new KnowledgeEmbeddingService(properties,mapper,new HashEmbeddingService(properties),observability,transport);
        embeddings.setSettings(new KnowledgeEmbeddingSettingsStore(jdbc));
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature='hash-v1:512:halfvec-v1' WHERE id=1");
        var chunker=new MarkdownKnowledgeChunker(properties);
        var vectors=new PgVectorKnowledgeService(jdbc,embeddings,properties,persistence,chunker,compression,sync);
        var index=new KnowledgeIndexService(vectors,properties,mock(RedisCacheService.class),mock(RagEvaluationService.class),
                chunker,observability,mock(KnowledgeRerankerService.class),new KnowledgeResultPostProcessor(properties));
        var worker=new KnowledgeMaintenanceService(store,embeddings,vectors,index,properties,Runnable::run,new SimpleMeterRegistry());
        vectors.add("admin","手工知识","保留这条管理员知识。");
        long id=store.requestBuild(embeddings.signatureForMode("SEMANTIC")+":halfvec-v1",512,"halfvec","SEMANTIC");
        worker.processBatch();
        long totalChunks=vectors.count();
        assertThat(totalChunks).isGreaterThan(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source='diet_guides.md'",Long.class))
                .isEqualTo(totalChunks-1);
        assertThat(embeddings.activeMode()).isEqualTo("LOCAL");
        assertThat(calls.get()).isEqualTo(5);
        embeddings.embed("检索仍使用本地向量");
        assertThat(calls.get()).isEqualTo(5);
        failNext.set(true);
        ready(id); worker.processBatch();
        assertThat(store.status(id).get("staged")).isEqualTo(5L);
        assertThat(embeddings.activeMode()).isEqualTo("LOCAL");
        finish(worker,id);
        assertThat(calls.get()).isEqualTo(Math.toIntExact(totalChunks)+1);
        assertThat(embeddings.activeMode()).isEqualTo("SEMANTIC");
        embeddings.embed("检索改用模型向量");
        assertThat(calls.get()).isEqualTo(Math.toIntExact(totalChunks)+2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source='admin'",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT embedding_signature) FROM knowledge_chunks",Long.class)).isEqualTo(1L);
        long local=store.requestBuild("hash-v1:512:halfvec-v1",512,"halfvec","LOCAL");
        finish(worker,local);
        assertThat(embeddings.activeMode()).isEqualTo("LOCAL");
        assertThat(calls.get()).isEqualTo(Math.toIntExact(totalChunks)+2);
        assertThat(vectors.count()).isEqualTo(totalChunks);
        assertThat(vectors.hasStaleEmbeddings()).isFalse();
    }
    private void ready(long id) {
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",id);
    }
    private void finish(KnowledgeMaintenanceService worker,long id) {
        long maxBatches=(((Number)store.status(id).get("totalChunks")).longValue()+4)/5+5;
        for(int batch=0;batch<maxBatches && !"SUCCEEDED".equals(store.status(id).get("status"));batch++) {
            ready(id);worker.processBatch();
        }
        assertThat(store.status(id).get("status")).isEqualTo("SUCCEEDED");
    }
}
