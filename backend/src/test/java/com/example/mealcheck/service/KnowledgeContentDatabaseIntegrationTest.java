package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({KnowledgeVectorPersistenceService.class,VectorCompressionService.class,AppProperties.class,
    KnowledgeContentService.class,ObjectMapper.class,KnowledgeRebuildStore.class,KnowledgeSourceSyncStore.class,
    KnowledgeRebuildPreviewService.class,MarkdownKnowledgeChunker.class})
@Testcontainers
class KnowledgeContentDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeVectorPersistenceService persistence;
    @Autowired KnowledgeContentService content;
    @Autowired KnowledgeRebuildPreviewService previews;
    @Autowired KnowledgeRebuildStore builds;
    @Autowired KnowledgeSourceSyncStore source;
    @Autowired AppProperties properties;
    @MockitoBean PgVectorKnowledgeService vectors;
    @MockitoBean KnowledgeEmbeddingService embeddings;
    @MockitoBean KnowledgeIndexService index;
    @MockitoBean KnowledgeEmbeddingProfileStore profiles;
    long id;
    final String signature="hash-v1:2:halfvec-v1";
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE knowledge_chunks CASCADE");jdbc.execute("TRUNCATE knowledge_content_versions,knowledge_embedding_rebuild_jobs,knowledge_source_sync CASCADE");
        jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding TYPE halfvec(2) USING '[1,0]'::halfvec(2)");
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature=? WHERE id=1",signature);
        when(embeddings.activeMode()).thenReturn("LOCAL");when(embeddings.signature()).thenReturn("hash-v1:2");
        when(embeddings.signatureForMode("LOCAL")).thenReturn("hash-v1:2");when(embeddings.dimensionForMode(anyString())).thenReturn(2);
        when(embeddings.activeDimension()).thenReturn(2);when(index.activeSplitterStrategy()).thenReturn("recursive-markdown");
        when(vectors.prepareContent(anyString(),anyString())).thenAnswer(call->row(call.getArgument(0),call.getArgument(1)));
        id=jdbc.queryForObject("INSERT INTO knowledge_chunks(source,chunk_key,title,content,embedding,embedding_signature,source_version) VALUES ('diet_guides.md','edited-key','原标题','原正文','[1,0]',?,'manual') RETURNING id",Long.class,signature);
    }
    KnowledgeVectorPersistenceService.KnowledgeVectorRow row(String title,String body) {
        return new KnowledgeVectorPersistenceService.KnowledgeVectorRow("unused",title,body,"general","[0,1]",signature,"a".repeat(64),3,"manual",LocalDateTime.now());
    }
    long revision() { return jdbc.queryForObject("SELECT content_revision FROM knowledge_chunks WHERE id=?",Long.class,id); }
    long version() { return jdbc.queryForObject("SELECT MAX(id) FROM knowledge_content_versions WHERE chunk_id=?",Long.class,id); }
    @Test void snapshotsTrackContentButNotVectorUsageOrHitCounts() {
        long before=version();
        jdbc.update("UPDATE knowledge_chunks SET embedding='[0,1]',hit_count=8,embedding_input_tokens=20 WHERE id=?",id);
        assertThat(version()).isEqualTo(before);assertThat(revision()).isEqualTo(1);
        persistence.update(id,row("新标题","新正文"),1L,"editor","修正说明");
        assertThat(revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT actor FROM knowledge_content_versions WHERE id=?",String.class,version())).isEqualTo("editor");
        assertThat(content.version(before).get("snapshot").toString()).contains("原正文");
    }
    @Test void staleEditAndDeleteCannotOverwriteOrRemoveNewContent() {
        persistence.update(id,row("最新","新内容"),1L,"a","");
        assertThatThrownBy(()->persistence.update(id,row("旧页面","过期草稿"),1L,"b","")).hasMessageContaining("其他操作修改");
        assertThatThrownBy(()->persistence.delete(id,1,"b")).hasMessageContaining("其他操作修改");
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks WHERE id=?",String.class,id)).isEqualTo("新内容");
        assertThat(revision()).isEqualTo(2);
    }
    @Test void deletedContentCanBeRestoredWithoutReusingOldRevisionOrVector() {
        long original=version();persistence.delete(id,1,"editor");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE id=?",Long.class,id)).isZero();
        content.restore(original,2,"restorer","误删恢复");
        assertThat(revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM knowledge_chunks WHERE id=?",String.class,id)).isEqualTo("[0,1]");
        assertThatThrownBy(()->content.restore(original,2,"late","")).hasMessageContaining("版本已变化");
        assertThat(revision()).isEqualTo(3);
    }
    @Test void restoringExistingContentCreatesOneNewVersion() {
        long original=version();persistence.update(id,row("changed","changed"),1L,"editor","");
        content.restore(original,2,"restorer","撤回修改");
        assertThat(revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks WHERE id=?",String.class,id)).isEqualTo("原正文");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_content_versions WHERE chunk_id=?",Long.class,id)).isEqualTo(3);
    }
    @Test void sourceKeyConflictPreservesDeletedHistoryAndCurrentReplacement() {
        long original=version();persistence.delete(id,1,"editor");
        jdbc.update("INSERT INTO knowledge_chunks(source,chunk_key,title,content,embedding) VALUES ('diet_guides.md','edited-key','替代条目','new','[1,0]')");
        assertThatThrownBy(()->content.restore(original,2,"restorer","")).hasMessageContaining("已被新知识占用");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks",Long.class)).isEqualTo(1);
        assertThat(content.history(id,true,0,10).get("totalElements")).isEqualTo(1L);
    }
    @Test void previewMakesNoProviderCallsAndListsManualDeletion() {
        var plan=previews.preview("LOCAL",null,"recursive-markdown");
        assertThat(plan.get("manualChanges")).isEqualTo(1);assertThat(plan.get("deleted")).isEqualTo(1);
        assertThat(plan.get("targetChunks")).isEqualTo(1351L);
        assertThat(plan.get("targetEmbeddingRequests")).isEqualTo(0L);
        verify(embeddings,never()).embed(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs",Long.class)).isZero();
    }
    @Test void stalePreviewIsRejectedAtSubmissionButHitsDoNotInvalidateIt() {
        var plan=previews.preview("LOCAL",List.of(id),null);
        jdbc.update("UPDATE knowledge_chunks SET hit_count=9 WHERE id=?",id);
        assertThat(previews.preview("LOCAL",List.of(id),null).get("previewToken")).isEqualTo(plan.get("previewToken"));
        jdbc.update("UPDATE knowledge_chunks SET content='concurrent' WHERE id=?",id);
        assertThatThrownBy(()->previews.submit("LOCAL",List.of(id),null,(String)plan.get("previewToken"),"admin")).hasMessageContaining("重新预览");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_jobs",Long.class)).isZero();
    }
    @Test void queuedPlanIsCheckedAgainBeforeSourceSync() {
        var plan=previews.preview("LOCAL",null,"recursive-markdown");
        long job=previews.submit("LOCAL",null,"recursive-markdown",(String)plan.get("previewToken"),"admin");
        var work=builds.claim(signature);
        jdbc.update("UPDATE knowledge_chunks SET content='concurrent' WHERE id=?",id);
        assertThatThrownBy(()->previews.verifyBeforeSourceSync(work)).isInstanceOf(KnowledgeJobFailure.EmbeddingException.class);
        assertThat(builds.status(job).get("status")).isEqualTo("RUNNING");
    }
    @Test void cancelledJobCannotPublishPreparedSource() {
        long job=builds.requestBuild(signature,2,"halfvec","LOCAL","recursive-markdown");
        var work=builds.claim(signature);
        long rev=jdbc.queryForObject("SELECT data_revision FROM knowledge_source_sync WHERE source='diet_guides.md'",Long.class);
        var lease=source.claim("diet_guides.md","frozen",signature,true,rev,work).lease();
        builds.cancel(job);
        assertThatThrownBy(()->source.publish(lease,List.of(),List.of(id))).isInstanceOf(KnowledgeSourceSyncStore.Conflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void selectedUnknownIdsAndConfigurationDriftAreRejected() {
        assertThatThrownBy(()->previews.preview("LOCAL",List.of(id+999),null)).hasMessageContaining("已删除");
        var plan=previews.preview("LOCAL",List.of(id),null);
        when(embeddings.signatureForMode("LOCAL")).thenReturn("changed");
        assertThatThrownBy(()->previews.submit("LOCAL",List.of(id),null,(String)plan.get("previewToken"),"admin")).hasMessageContaining("重新预览");
    }
    @Test void migrationPreservesPopulatedV43ContentAsBaselineHistory() {
        String url=POSTGRES.getJdbcUrl()+(POSTGRES.getJdbcUrl().contains("?")?"&":"?")+"currentSchema=history_upgrade,public";
        var dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword());
        org.flywaydb.core.Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock","false")).dataSource(dataSource).schemas("history_upgrade").defaultSchema("history_upgrade").target("43").load().migrate();
        var old=new JdbcTemplate(dataSource);
        long original=old.queryForObject("INSERT INTO knowledge_chunks(source,title,content,embedding) VALUES ('admin','升级前标题','必须保留的既有正文',?::halfvec(512)) RETURNING id",Long.class,"[1"+",0".repeat(511)+"]");
        org.flywaydb.core.Flyway.configure().configuration(Map.of("flyway.postgresql.transactional.lock","false")).dataSource(dataSource).schemas("history_upgrade").defaultSchema("history_upgrade").load().migrate();
        assertThat(old.queryForObject("SELECT content FROM knowledge_chunks WHERE id=?",String.class,original)).isEqualTo("必须保留的既有正文");
        assertThat(old.queryForObject("SELECT snapshot->>'content' FROM knowledge_content_versions WHERE chunk_id=?",String.class,original)).isEqualTo("必须保留的既有正文");
        assertThat(old.queryForObject("SELECT operation FROM knowledge_content_versions WHERE chunk_id=?",String.class,original)).isEqualTo("BASELINE");
    }
    @Test void simultaneousEditorsCannotBothPublishTheSameRevision() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate=new java.util.concurrent.CountDownLatch(1);
        var succeeded=new java.util.concurrent.atomic.AtomicInteger();
        var conflicted=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var tasks=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<2;i++) {
                final int editor=i;
                tasks.add(pool.submit(()-> {
                    try { gate.await(); persistence.update(id,row("编辑"+editor,"正文"+editor),1L,"editor-"+editor,"");succeeded.incrementAndGet(); }
                    catch(org.springframework.web.server.ResponseStatusException error) {
                        assertThat(error.getStatusCode().value()).isEqualTo(409);conflicted.incrementAndGet();
                    } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
                }));
            }
            gate.countDown();for(var task:tasks) task.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(succeeded.get()).isEqualTo(1);assertThat(conflicted.get()).isEqualTo(1);assertThat(revision()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
}
