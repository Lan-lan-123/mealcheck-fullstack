package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({KnowledgeSourceSyncStore.class,KnowledgeVectorPersistenceService.class,VectorCompressionService.class,AppProperties.class})
@Testcontainers
class KnowledgeSourceSyncDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired KnowledgeSourceSyncStore store;
    @Autowired KnowledgeVectorPersistenceService persistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    static final String SOURCE="diet_guides.md";
    @Test void sourceReusePreservesUsageAndRegenerationWithoutUsageClearsIt() {
        var initial = new KnowledgeVectorPersistenceService.KnowledgeSyncRow(null,"key",0,"title","body","general","[1,0]",
                "test:halfvec-v1","a".repeat(64),2,"v1",LocalDateTime.now(),19L,19L);
        store.publish(claim("first",true),List.of(initial),List.of());
        long id=jdbc.queryForObject("SELECT id FROM knowledge_chunks",Long.class);
        var unchanged = new KnowledgeVectorPersistenceService.KnowledgeSyncRow(id,"key",0,"title","body","general",null,
                "test:halfvec-v1","a".repeat(64),2,"v2",LocalDateTime.now());
        store.publish(claim("second",true),List.of(unchanged),List.of());
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks",Long.class)).isEqualTo(19L);
        persistence.update(id,new KnowledgeVectorPersistenceService.KnowledgeVectorRow("key","title","new body","general","[0,1]",
                "test:halfvec-v1","b".repeat(64),3,"v3",LocalDateTime.now()));
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT embedding_total_tokens FROM knowledge_chunks",Long.class)).isNull();
    }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE knowledge_chunks CASCADE");jdbc.execute("TRUNCATE knowledge_source_sync");
        jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding TYPE halfvec(2) USING '[1,0]'::halfvec(2)");
    }
    KnowledgeSourceSyncStore.Lease claim(String fingerprint,boolean explicit) {return store.claim(SOURCE,fingerprint,"test:halfvec-v1",explicit).lease();}
    KnowledgeVectorPersistenceService.KnowledgeSyncRow row(String key,String vector) {
        return new KnowledgeVectorPersistenceService.KnowledgeSyncRow(null,key,0,"title","body","general",vector,
                "test:halfvec-v1","a".repeat(64),2,"v1",LocalDateTime.now());
    }
    void publish(KnowledgeSourceSyncStore.Lease lease) {store.publish(lease,List.of(row("key","[1,0]")),List.of());}
    void expire() {jdbc.update("UPDATE knowledge_source_sync SET lease_until=clock_timestamp()-INTERVAL '1 second'");}
    void conflict(Runnable call,KnowledgeSourceSyncStore.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(KnowledgeSourceSyncStore.Conflict.class,error->assertThat(error.reason()).isEqualTo(reason));
    }

    @Test void simultaneousClaimsAdmitOnlyOneWorkerAndReturnBusyForOtherVersions() throws Exception {
        var workers=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var calls=new ArrayList<Future<KnowledgeSourceSyncStore.Lease>>();
            for(int i=0;i<2;i++)calls.add(workers.submit(()->{start.await();try{return claim("same",false);}
                catch(KnowledgeSourceSyncStore.Conflict busy){assertThat(busy.reason()).isEqualTo(KnowledgeSourceSyncStore.Reason.BUSY);return null;}}));
            start.countDown();var accepted=new ArrayList<KnowledgeSourceSyncStore.Lease>();
            for(var call:calls){var lease=call.get(8,TimeUnit.SECONDS);if(lease!=null)accepted.add(lease);}
            assertThat(accepted).hasSize(1);
            conflict(()->claim("different",true),KnowledgeSourceSyncStore.Reason.BUSY);
            publish(accepted.get(0));assertThat(store.claim(SOURCE,"same","test:halfvec-v1",false).current()).isTrue();
        } finally {workers.shutdownNow();}
    }
    @Test void expiredWorkerCannotRenewPublishOrReleaseAReplacement() {
        var old=claim("old",false);expire();
        assertThat(store.status().get(0).get("state")).isEqualTo("INTERRUPTED");
        var current=claim("new",true);
        conflict(()->store.renew(old),KnowledgeSourceSyncStore.Reason.EXPIRED);
        conflict(()->publish(old),KnowledgeSourceSyncStore.Reason.EXPIRED);
        store.release(old,"SYNC_FAILED");
        conflict(()->claim("another",true),KnowledgeSourceSyncStore.Reason.BUSY);
        publish(current);assertThat(store.status().get(0).get("completed_fingerprint")).isEqualTo("new");
    }
    @Test void completedFingerprintSkipsRestartAndPreservesManualEdits() {
        publish(claim("one",false));
        jdbc.update("UPDATE knowledge_chunks SET content='manual edit'");
        var before=jdbc.queryForMap("SELECT data_revision,completed_at FROM knowledge_source_sync WHERE source=?",SOURCE);
        assertThat(store.claim(SOURCE,"one","test:halfvec-v1",false).current()).isTrue();
        assertThat(jdbc.queryForMap("SELECT data_revision,completed_at FROM knowledge_source_sync WHERE source=?",SOURCE)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks",String.class)).isEqualTo("manual edit");
        conflict(()->claim("two",false),KnowledgeSourceSyncStore.Reason.REVIEW_REQUIRED);
    }
    @Test void explicitPromotionPreventsOldAutomaticInstancesFromRevertingTheSource() {
        publish(claim("old",false));
        var next=claim("new",true);store.publish(next,List.of(),List.of());
        conflict(()->claim("old",false),KnowledgeSourceSyncStore.Reason.REVIEW_REQUIRED);
        assertThat(store.claim(SOURCE,"new","test:halfvec-v1",false).current()).isTrue();
    }
    @Test void editsDuringPreparationFencePublicationAndRequireReviewBeforeRetry() {
        var lease=claim("initial",false);
        jdbc.update("INSERT INTO knowledge_chunks(source,chunk_key,content,embedding) VALUES (?,'manual','edited','[0,1]')",SOURCE);
        conflict(()->publish(lease),KnowledgeSourceSyncStore.Reason.EDITED);
        store.release(lease,"EDITED");
        conflict(()->claim("initial",false),KnowledgeSourceSyncStore.Reason.EDITED);
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks",String.class)).isEqualTo("edited");
        var reviewed=claim("initial",true);store.publish(reviewed,List.of(),List.of());
        assertThat(store.status().get(0).get("state")).isEqualTo("SUCCEEDED");
    }
    @Test void invalidVectorRollsBackEveryChunkAndTheCompletionMarker() {
        var lease=claim("initial",false);
        assertThatThrownBy(()->store.publish(lease,List.of(row("first","[1,0]"),row("second","[1,0,0]")),List.of()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks",Long.class)).isZero();
        assertThat(store.status().get(0).get("completed_fingerprint")).isNull();
        store.release(lease,"SYNC_FAILED");publish(claim("initial",false));
    }
    @Test void splitterIsPublishedWithTheSourceAndFailedPublicationKeepsPreviousSelection() {
        store.publish(claim("first",true),List.of(row("first","[1,0]")),List.of(),"langchain4j-recursive");
        assertThat(store.splitterStrategy(SOURCE)).isEqualTo("langchain4j-recursive");
        var failed = claim("second",true);
        assertThatThrownBy(() -> store.publish(failed,List.of(row("second","[1,0,0]")),List.of(),"structural-markdown"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.splitterStrategy(SOURCE)).isEqualTo("langchain4j-recursive");
        store.release(failed,"SYNC_FAILED");
        store.publish(claim("third",true),List.of(row("third","[0,1]")),List.of(),"structural-markdown");
        assertThat(store.splitterStrategy(SOURCE)).isEqualTo("structural-markdown");
    }
    @Test void HitCountersAndOtherSourcesDoNotInvalidateThisSource() {
        jdbc.update("INSERT INTO knowledge_chunks(source,chunk_key,content,embedding) VALUES (?,'existing','body','[0,1]')",SOURCE);
        var lease=claim("initial",false);
        jdbc.update("UPDATE knowledge_chunks SET hit_count=1,last_hit_at=CURRENT_TIMESTAMP");
        jdbc.update("INSERT INTO knowledge_chunks(source,content,embedding) VALUES ('manual','other','[0,1]')");
        publish(lease);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks WHERE source=?",Long.class,SOURCE)).isEqualTo(2);
    }
    @Test void truncateInvalidatesPreparedWork() {
        var lease=claim("initial",false);jdbc.execute("TRUNCATE knowledge_chunks CASCADE");
        conflict(()->publish(lease),KnowledgeSourceSyncStore.Reason.EDITED);
    }
    @Test void concurrentEditorCommitsBeforePublicationAndIsNotOverwritten() throws Exception {
        jdbc.update("INSERT INTO knowledge_chunks(source,chunk_key,content,embedding) VALUES (?,'existing','body','[0,1]')",SOURCE);
        var lease=claim("initial",false);var edited=new CountDownLatch(1);var commit=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try {
            var edit=worker.submit(()->new TransactionTemplate(transactions).execute(status->{
                jdbc.update("UPDATE knowledge_chunks SET content='human edit' WHERE source=?",SOURCE);edited.countDown();
                try{if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                catch(InterruptedException error){throw new IllegalStateException(error);}return null;
            }));
            assertThat(edited.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->publish(lease)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            commit.countDown();edit.get(5,TimeUnit.SECONDS);
            conflict(()->publish(lease),KnowledgeSourceSyncStore.Reason.EDITED);
            assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks",String.class)).isEqualTo("human edit");
        } finally {commit.countDown();worker.shutdownNow();}
    }
}
