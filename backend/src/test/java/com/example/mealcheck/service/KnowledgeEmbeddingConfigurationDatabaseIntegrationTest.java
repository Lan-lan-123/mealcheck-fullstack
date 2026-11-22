package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false",
        "mealcheck.knowledge.auto-index=false","mealcheck.knowledge.embedding-api-key=old-key",
        "mealcheck.knowledge.embedding-base-url=https://old.test/v1","mealcheck.knowledge.embedding-model=old-model",
        "mealcheck.knowledge.embedding-dim=3","mealcheck.ai-settings.encryption-key=embedding-test-secret"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(AppProperties.class)
@Import({KnowledgeEmbeddingConfigurationService.class,KnowledgeEmbeddingProfileStore.class,KnowledgeEmbeddingService.class,
        KnowledgeEmbeddingSettingsStore.class,HashEmbeddingService.class,KnowledgeRebuildStore.class,KnowledgeJobLifecycleService.class,
        VectorCompressionService.class,AssistantMemoryStore.class,KnowledgeEmbeddingConfigurationDatabaseIntegrationTest.Json.class,
        KnowledgeRebuildPreviewService.class,MarkdownKnowledgeChunker.class})
@Testcontainers
class KnowledgeEmbeddingConfigurationDatabaseIntegrationTest {
    @TestConfiguration static class Json { @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); } }
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired KnowledgeEmbeddingConfigurationService settings;
    @Autowired KnowledgeEmbeddingProfileStore profiles;
    @Autowired KnowledgeEmbeddingService embeddings;
    @Autowired KnowledgeRebuildStore jobs;
    @Autowired KnowledgeJobLifecycleService lifecycle;
    @Autowired KnowledgeRebuildPreviewService previews;
    @Autowired AssistantMemoryStore memories;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean KnowledgeIndexService index;
    @MockBean AiHttpTransport transport;
    @MockBean ApplicationObservability observability;
    @MockBean AdminAuditService audit;
    final UserPrincipal admin=mock(UserPrincipal.class);

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        properties.getKnowledge().setEmbeddingApiKey("old-key");
        properties.getKnowledge().setEmbeddingBaseUrl("https://old.test/v1");
        jdbc.execute("TRUNCATE knowledge_embedding_rebuild_jobs,knowledge_chunks,users,assistant_memory_rebuild_jobs CASCADE");
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='SEMANTIC',signature=NULL,active_profile_id=NULL,draft_profile_id=NULL,configuration_version=0 WHERE id=1");
        jdbc.update("DELETE FROM knowledge_embedding_profiles");
        jdbc.update("UPDATE assistant_memory_version SET signature=NULL WHERE id=1");
        jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding TYPE vector(3) USING '[1,0,0]'::vector(3)");
        String signature=embeddings.signature()+":halfvec-v1";
        jdbc.update("UPDATE knowledge_embedding_settings SET signature=? WHERE id=1",signature);
        jdbc.update("INSERT INTO knowledge_chunks(source,title,content,embedding,embedding_signature) VALUES ('admin','手工知识','蔬菜与蛋白质','[1,0,0]',?)",signature);
        when(admin.getId()).thenReturn(1L);
        reset(transport,audit);
        when(transport.send(any(),any(),any())).thenAnswer(call -> {
            var body=mapper.readTree(call.getArgument(2,String.class));
            int size=body.path("model").asText().equals("model-two")?2:3;
            var response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            float[] vector=new float[size]; vector[0]=1;
            when(response.body()).thenReturn("{\"data\":[{\"embedding\":"+mapper.writeValueAsString(vector)+"}]}");
            return response;
        });
    }
    private KnowledgeEmbeddingConfigurationService.View save(String model,int dimension,String key) {
        return settings.save(new KnowledgeEmbeddingConfigurationService.Update(model,"https://new.test/v1",key,dimension,false,settings.view().version()),admin);
    }
    private KnowledgeEmbeddingConfigurationService.Version version() {
        var view=settings.view();
        String token=view.draft()!=null&&view.draft().testedAt()!=null
                ?(String)settings.preview(new KnowledgeEmbeddingConfigurationService.Version(view.version())).get("previewToken"):null;
        return new KnowledgeEmbeddingConfigurationService.Version(view.version(),token);
    }
    private KnowledgeMaintenanceService worker() {
        var vectors=mock(PgVectorKnowledgeService.class);
        when(vectors.configuredSignature()).thenAnswer(call->embeddings.signature()+":halfvec-v1");
        var worker=new KnowledgeMaintenanceService(jobs,embeddings,vectors,mock(KnowledgeIndexService.class),properties,Runnable::run,new SimpleMeterRegistry());
        worker.setProfiles(profiles); worker.setLifecycle(lifecycle);
        return worker;
    }
    @Test void draftsAreEncryptedVersionedAndCannotBeAppliedBeforeSuccessfulTest() throws Exception {
        var initial=settings.view();
        var saved=save("model-two",2,"private-key");
        assertThat(saved.active().model()).isEqualTo("old-model");
        assertThat(embeddings.activeDimension()).isEqualTo(3);
        assertThat(saved.rebuildRequired()).isTrue();
        assertThat(mapper.writeValueAsString(saved)).doesNotContain("private-key","apiKey\"");
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM knowledge_embedding_profiles WHERE source='ADMIN'",String.class)).doesNotContain("private-key");
        assertThatThrownBy(()->settings.apply(version(),admin)).hasMessageContaining("测试");
        assertThatThrownBy(()->settings.save(new KnowledgeEmbeddingConfigurationService.Update("other","https://new.test/v1","",2,false,initial.version()),admin)).hasMessageContaining("刷新");
        assertThatThrownBy(()->settings.save(new KnowledgeEmbeddingConfigurationService.Update("other","https://another.test/v1","",2,false,saved.version()),admin)).hasMessageContaining("API Key");
        assertThatThrownBy(()->settings.save(new KnowledgeEmbeddingConfigurationService.Update("other","https://key@new.test/v1","x",2,false,saved.version()),admin)).hasMessageContaining("Base URL");
        assertThat(settings.test(version(),admin).success()).isTrue();
        var replica=new KnowledgeEmbeddingProfileStore(jdbc,new AppProperties(),"embedding-test-secret");
        assertThat(replica.draft().profile().apiKey()).isEqualTo("private-key");
        assertThat(replica.active().profile().model()).isEqualTo("old-model");
    }
    @Test void changedDraftCannotAlterRunningJobAndActivationSwitchesVectorsAndProfileTogether() throws Exception {
        save("model-two",2,"pinned-key"); settings.test(version(),admin);
        var applied=settings.apply(version(),admin);
        long id=((Number)applied.job().get("id")).longValue();
        var pinned=profiles.draft();
        save("model-three",3,"next-key");
        lifecycle.reconcile();
        assertThat(jobs.status(id).get("status")).isEqualTo("PENDING");
        assertThat(embeddings.semanticModel()).isEqualTo("old-model");
        worker().processBatch();
        assertThat(jobs.status(id).get("status")).isEqualTo("SUCCEEDED");
        assertThat(profiles.active().id()).isEqualTo(pinned.id());
        assertThat(embeddings.semanticModel()).isEqualTo("model-two");
        assertThat(embeddings.activeDimension()).isEqualTo(2);
        assertThat(settings.view().draft().model()).isEqualTo("model-three");
        assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM knowledge_chunks",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks",String.class)).isEqualTo(embeddings.signature()+":halfvec-v1");
        verify(transport,atLeastOnce()).send(eq("embedding"),argThat(request -> request.headers().firstValue("Authorization").orElse("").equals("Bearer pinned-key")),anyString());
    }
    @Test void failuresAndCancellationPreserveActiveProfileAndCredentialRotationNeedsNoRebuild() throws Exception {
        save("model-two",2,"pinned-key"); settings.test(version(),admin);
        long id=((Number)settings.apply(version(),admin).job().get("id")).longValue();
        var work=jobs.claim("unused");
        jobs.fail(work,KnowledgeJobFailure.http(401));
        assertThat(embeddings.semanticModel()).isEqualTo("old-model");
        lifecycle.retry(id); worker().processBatch();
        assertThat(embeddings.semanticModel()).isEqualTo("model-two");
        long count=jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs",Long.class);
        save("model-two",2,"rotated-key"); settings.test(version(),admin);
        assertThat(settings.apply(version(),admin).rebuilding()).isFalse();
        assertThat(profiles.active().profile().apiKey()).isEqualTo("rotated-key");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs",Long.class)).isEqualTo(count);
        save("model-three",3,"next-key"); settings.test(version(),admin);
        id=((Number)settings.apply(version(),admin).job().get("id")).longValue();
        var cancelled=jobs.claim("unused"); jobs.cancel(id);
        assertThat(jobs.activate(cancelled)).isFalse();
        assertThat(embeddings.semanticModel()).isEqualTo("model-two");
    }
    @Test void actualDimensionMustMatchAndAuditFailureRollsBackDraft() {
        var saved=save("model-two",3,"private-key");
        var tested=settings.test(version(),admin);
        assertThat(tested.success()).isFalse();
        assertThat(tested.message()).contains("维度");
        assertThat(settings.view().draft().testedAt()).isNull();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).log(any(),eq("UPDATE_EMBEDDING_SETTINGS"),anyString(),anyLong(),anyString());
        assertThatThrownBy(()->save("model-three",3,"another-key")).hasMessageContaining("audit unavailable");
        assertThat(settings.view().version()).isEqualTo(saved.version());
        assertThat(settings.view().draft().model()).isEqualTo("model-two");
    }
    @Test void memoryMaintenanceReplacesObsoleteJobsAndUsesNewDimensionAfterSwitch() {
        long user=jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES ('embedding-memory','hash') RETURNING id",Long.class);
        long conversation=jdbc.queryForObject("INSERT INTO assistant_conversations(user_id,title) VALUES (?,'memory') RETURNING id",Long.class,user);
        memories.saveText(user,conversation,"restriction","我对花生过敏","a".repeat(64),0.9);
        String old=embeddings.signature()+":halfvec-v1";
        long oldJob=memories.request(old,3,"halfvec");
        var oldWork=memories.claim(old);
        save("model-two",2,"new-key"); settings.test(version(),admin); settings.apply(version(),admin); worker().processBatch();
        var memoryWorker=new AssistantMemoryMaintenanceService(memories,embeddings,new VectorCompressionService(properties),properties,Runnable::run,new SimpleMeterRegistry());
        memoryWorker.processBatch();
        assertThat(memories.status(oldJob).get("status")).isEqualTo("FAILED");
        assertThat(memories.stage(oldWork,new AssistantMemoryStore.Fact(1,"old","old"),"[1,0,0]")).isFalse();
        assertThat(memories.ready(embeddings.signature()+":halfvec-v1")).isTrue();
        assertThat(jdbc.queryForObject("SELECT dimension FROM assistant_memory_vectors",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT fact_text FROM assistant_memory_facts",String.class)).isEqualTo("我对花生过敏");
    }
    @Test void firstRemoteProfileCanBeReappliedWithFreshPreviewWhenNoDeploymentEndpointExists() {
        properties.getKnowledge().setEmbeddingApiKey(""); properties.getKnowledge().setEmbeddingBaseUrl("");
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature='hash-v1:3:halfvec-v1' WHERE id=1");
        assertThat(embeddings.isSemanticAvailable()).isFalse();
        save("model-two",2,"first-key"); settings.test(version(),admin);
        long id=((Number)settings.apply(version(),admin).job().get("id")).longValue();
        jobs.cancel(id);
        assertThat(embeddings.activeMode()).isEqualTo("LOCAL");
        assertThatThrownBy(() -> lifecycle.resubmit(id,"SEMANTIC")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        long replacement=((Number)settings.apply(version(),admin).job().get("id")).longValue();
        worker().processBatch();
        assertThat(jobs.status(replacement).get("status")).isEqualTo("SUCCEEDED");
        assertThat(embeddings.activeMode()).isEqualTo("SEMANTIC");
        assertThat(embeddings.activeDimension()).isEqualTo(2);
    }

    @Test void resubmissionRequiresFreshPreviewAndNeverSilentlyUsesSavedDraft() {
        String signature=embeddings.signature()+":halfvec-v1";
        long id=jobs.requestBuild(signature,3,"halfvec","SEMANTIC");
        jobs.cancel(id);
        save("model-two",2,"untested-draft-key");
        assertThatThrownBy(() -> lifecycle.resubmit(id,"SEMANTIC")).hasMessageContaining("预览");
        String token=(String)previews.preview("SEMANTIC",null,null).get("previewToken");
        jdbc.update("UPDATE knowledge_chunks SET content='changed after preview'");
        assertThatThrownBy(() -> lifecycle.resubmit(id,"SEMANTIC",null,token,"admin")).hasMessageContaining("预览");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs",Long.class)).isEqualTo(1);
        String fresh=(String)previews.preview("SEMANTIC",null,null).get("previewToken");
        long replacement=lifecycle.resubmit(id,"SEMANTIC",null,fresh,"admin");
        assertThat(jobs.status(replacement).get("signature")).isEqualTo(signature);
        assertThat(jobs.status(replacement).get("profile_id")).isNull();
        assertThat(embeddings.semanticModel()).isEqualTo("old-model");
        assertThat(settings.view().draft().model()).isEqualTo("model-two");
    }

    @Test @SuppressWarnings("unchecked")
    void bge1024SwitchUpdatesRealStorageQueriesAndMemoryWithoutChangingLegacyDefaults() throws Exception {
        float[] vector=new float[1024]; vector[0]=1e-20f;
        doAnswer(call -> {
            var body=mapper.readTree(call.getArgument(2,String.class));
            assertThat(body.path("model").asText()).isEqualTo("BAAI/bge-m3");
            assertThat(body.has("dimensions")).isFalse();
            var response=mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"data\":[{\"embedding\":"+mapper.writeValueAsString(vector)+"}]}");
            return response;
        }).when(transport).send(any(),any(),any());
        save("BAAI/bge-m3",1024,"bge-fixture-key");
        assertThat(settings.test(version(),admin).success()).isTrue();
        settings.apply(version(),admin); worker().processBatch();
        assertThat(properties.getKnowledge().getEmbeddingDim()).isEqualTo(3);
        assertThat(embeddings.activeDimension()).isEqualTo(1024);
        assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM knowledge_chunks",Integer.class)).isEqualTo(1024);
        assertThat(jdbc.queryForObject("SELECT embedding <#> embedding FROM knowledge_chunks",Double.class)).isLessThan(-0.99);
        var vectors=new PgVectorKnowledgeService(jdbc,embeddings,properties,mock(KnowledgeVectorPersistenceService.class),
                new MarkdownKnowledgeChunker(properties),new VectorCompressionService(properties),mock(KnowledgeSourceSyncStore.class));
        assertThat(vectors.storageMatchesConfiguration()).isTrue();
        assertThat(vectors.search("蔬菜",3)).isNotEmpty();
        long user=jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES ('bge-memory','hash') RETURNING id",Long.class);
        long conversation=jdbc.queryForObject("INSERT INTO assistant_conversations(user_id,title) VALUES (?,'memory') RETURNING id",Long.class,user);
        memories.saveText(user,conversation,"restriction","我对花生过敏","b".repeat(64),0.9);
        var worker=new AssistantMemoryMaintenanceService(memories,embeddings,new VectorCompressionService(properties),properties,Runnable::run,new SimpleMeterRegistry());
        worker.processBatch();
        assertThat(memories.ready(embeddings.signature()+":halfvec-v1")).isTrue();
        assertThat(jdbc.queryForObject("SELECT dimension FROM assistant_memory_vectors",Integer.class)).isEqualTo(1024);
        assertThat(jdbc.queryForObject("SELECT fact_text FROM assistant_memory_facts",String.class)).isEqualTo("我对花生过敏");
    }
    @Test void inventoryPreservesModelNamesContainingColons() {
        save("model:three",3,"model-key"); settings.test(version(),admin); settings.apply(version(),admin); worker().processBatch();
        var chunks=(java.util.List<java.util.Map<String,Object>>)jobs.inventory(embeddings.signature()+":halfvec-v1").get("chunks");
        assertThat(chunks.get(0).get("model")).isEqualTo("model:three");
        assertThat(chunks.get(0).get("compatible")).isEqualTo(true);
    }
}
