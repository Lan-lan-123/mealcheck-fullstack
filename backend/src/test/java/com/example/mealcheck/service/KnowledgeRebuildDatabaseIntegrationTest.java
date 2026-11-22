package com.example.mealcheck.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(KnowledgeRebuildStore.class)
@Testcontainers
class KnowledgeRebuildDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired KnowledgeRebuildStore store;
    @Autowired JdbcTemplate jdbc;
    long chunkId;
    @Test void reportedUsageWaitsForVectorActivationAndLocalReplacementClearsIt() {
        jdbc.update("UPDATE knowledge_chunks SET embedding_input_tokens=5,embedding_total_tokens=5 WHERE id=?",chunkId);
        long id=store.requestBuild("target",3,"vector","SEMANTIC");
        var work=store.claim("target");
        store.sourceSynced(work);
        store.stage(work,store.pending(work,5).get(0),"[0,1,0]",31L,31L);
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isEqualTo(5L);
        assertThat(store.activate(work)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isEqualTo(31L);
        assertThat(jdbc.queryForObject("SELECT embedding_total_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isEqualTo(31L);
        long local=store.requestBuild("local",3,"vector","LOCAL");
        var replacement=store.claim("local");
        store.sourceSynced(replacement);
        store.stage(replacement,store.pending(replacement,5).get(0),"[1,0,0]");
        assertThat(store.activate(replacement)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isNull();
        assertThat(jdbc.queryForObject("SELECT embedding_total_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isNull();
    }

    @Test void selectedUsageSurvivesRetryAndLeavesOtherChunksUntouched() {
        String signature="semantic-v1:fixture:3:abc:vector-v1";
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='SEMANTIC',signature=? WHERE id=1",signature);
        jdbc.update("UPDATE knowledge_chunks SET embedding_signature=?,embedding_input_tokens=5,embedding_total_tokens=5 WHERE id=?",signature,chunkId);
        long untouched=jdbc.queryForObject("INSERT INTO knowledge_chunks(source,title,content,embedding,embedding_signature,embedding_input_tokens,embedding_total_tokens) VALUES ('admin','keep','body','[1,0,0]',?,7,7) RETURNING id",Long.class,signature);
        long id=store.requestSelected(signature,3,"vector","SEMANTIC",java.util.List.of(chunkId));
        var work=store.claim(signature);
        store.stage(work,store.pending(work,5).get(0),"[0,1,0]",23L,23L);
        store.fail(work,new KnowledgeJobFailure("TEST_FAILURE","test",false));
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isEqualTo(5L);
        store.retry(id,signature);
        var retry=store.claim(signature);
        assertThat(store.pending(retry,5)).isEmpty();
        assertThat(store.activate(retry)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks WHERE id=?",Long.class,chunkId)).isEqualTo(23L);
        assertThat(jdbc.queryForObject("SELECT embedding_input_tokens FROM knowledge_chunks WHERE id=?",Long.class,untouched)).isEqualTo(7L);
    }
    @Test void jobSplitterSurvivesClaimRetryAndRejectsAnotherConcurrentChoice() {
        long id = store.requestBuild("target",3,"vector","LOCAL","structural-markdown");
        assertThat(store.status(id).get("splitter_strategy")).isEqualTo("structural-markdown");
        assertThat(store.requestBuild("target",3,"vector","LOCAL","structural-markdown")).isEqualTo(id);
        assertThatThrownBy(() -> store.requestBuild("target",3,"vector","LOCAL","langchain4j-recursive"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var work = store.claim("target");
        assertThat(work.splitterStrategy()).isEqualTo("structural-markdown");
        store.fail(work,new KnowledgeJobFailure("TEST_FAILURE","test",false));
        store.retry(id,"target");
        assertThat(store.claim("target").splitterStrategy()).isEqualTo("structural-markdown");
    }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE knowledge_embedding_rebuild_jobs,knowledge_chunks CASCADE");
        jdbc.update("UPDATE knowledge_embedding_settings SET mode=NULL,signature=NULL WHERE id=1");
        jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding TYPE vector(3) USING '[1,0,0]'::vector(3)");
        chunkId=jdbc.queryForObject("INSERT INTO knowledge_chunks(source,title,content,embedding,embedding_signature) VALUES ('manual','title','original','[1,0,0]','old') RETURNING id", Long.class);
    }
    @Test void selectedModeOnlyChangesWithCompleteVectorsAndSyncedSource() {
        long id = store.requestBuild("semantic-target",2,"halfvec","SEMANTIC");
        assertThat(store.requestBuild("semantic-target",2,"halfvec","SEMANTIC")).isEqualTo(id);
        assertThatThrownBy(() -> store.requestBuild("local-target",2,"halfvec","LOCAL"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var work = store.claimRequested("local-target","semantic-target");
        assertThat(work.mode()).isEqualTo("SEMANTIC");
        assertThat(store.stage(work,store.pending(work,5).get(0),"[0,1]")).isTrue();
        assertThat(store.activate(work)).isFalse();
        assertThat(jdbc.queryForObject("SELECT mode FROM knowledge_embedding_settings WHERE id=1",String.class)).isNull();
        assertThat(store.sourceSynced(work)).isTrue();
        assertThat(store.activate(work)).isTrue();
        assertThat(jdbc.queryForObject("SELECT mode FROM knowledge_embedding_settings WHERE id=1",String.class)).isEqualTo("SEMANTIC");
        assertThat(jdbc.queryForObject("SELECT signature FROM knowledge_embedding_settings WHERE id=1",String.class)).isEqualTo("semantic-target");
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("semantic-target");
    }
    @Test void cancellationFencesInFlightWorkerAndReleasesTaskSlot() {
        long id=store.requestBuild("target",2,"halfvec","LOCAL");
        var work=store.claim("target");
        var chunk=store.pending(work,5).get(0);
        store.sourceSynced(work);
        store.cancel(id);
        assertThat(store.canContinue(work)).isFalse();
        assertThat(store.stage(work,chunk,"[0,1]")).isFalse();
        assertThat(store.activate(work)).isFalse();
        store.fail(work,KnowledgeJobFailure.http(429));
        assertThat(store.status(id).get("status")).isEqualTo("CANCELLED");
        assertThat(store.status(id).get("finished_at")).isNotNull();
        assertThat(store.requestBuild("target",2,"halfvec","LOCAL")).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks",String.class)).isEqualTo("old");
    }
    @Test void configurationChangeInvalidatesLeasedAndQueuedJobsWithoutPublishingStaging() {
        long id=store.requestBuild("semantic-v1:old:2:abc:halfvec-v1",2,"halfvec","SEMANTIC");
        var work=store.claim("semantic-v1:old:2:abc:halfvec-v1");
        store.sourceSynced(work);
        store.stage(work,store.pending(work,5).get(0),"[0,1]");
        store.invalidateIncompatible("hash-v1:2:halfvec-v1","semantic-v1:new:2:abc:halfvec-v1","hash-v1:2:halfvec-v1");
        assertThat(store.status(id).get("status")).isEqualTo("INVALIDATED");
        assertThat(store.status(id).get("last_error")).isEqualTo("CONFIG_CHANGED");
        assertThat(store.activate(work)).isFalse();
        long queued=store.requestBuild("semantic-v1:new:2:abc:halfvec-v1",2,"halfvec","SEMANTIC");
        store.invalidateIncompatible("hash-v1:2:halfvec-v1",null,"hash-v1:2:halfvec-v1");
        assertThat(store.status(queued).get("last_error")).isEqualTo("CONFIG_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks",String.class)).isEqualTo("old");
        assertThat(store.history(0,1).get("totalElements")).isEqualTo(2L);
        assertThat((java.util.List<?>)store.details(id).get("events")).hasSize(3);
    }
    @Test void permanentFailureStopsImmediatelyWhileTransientFailureRetriesAndPreservesHistory() {
        long id=store.requestBuild("target",2,"halfvec","LOCAL");
        var work=store.claim("target");
        store.stage(work,store.pending(work,5).get(0),"[0,1]");
        store.fail(work,KnowledgeJobFailure.http(401));
        assertThat(store.status(id).get("status")).isEqualTo("FAILED");
        assertThat(store.status(id).get("last_error")).isEqualTo("EMBEDDING_AUTH");
        assertThat(store.status(id).get("staged")).isEqualTo(1L);
        store.retry(id,"target");
        assertThat(store.status(id).get("failure_summary")).isNull();
        work=store.claim("target");
        store.fail(work,KnowledgeJobFailure.http(429));
        assertThat(store.status(id).get("status")).isEqualTo("RETRY");
        assertThat(store.status(id).get("finished_at")).isNull();
        assertThat(store.claim("target")).isNull();
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET next_attempt_at=CURRENT_TIMESTAMP,attempts=4 WHERE id=?",id);
        work=store.claim("target");
        store.fail(work,KnowledgeJobFailure.http(503));
        assertThat(store.status(id).get("status")).isEqualTo("FAILED");
        assertThat(store.status(id).get("finished_at")).isNotNull();
        assertThat((java.util.List<?>)store.details(id).get("events")).hasSize(8);
    }
    @Test void failedModeBuildResumesStagedItemsOnlyAfterExplicitSubmission() {
        long id = store.requestBuild("target",2,"halfvec","LOCAL");
        var work = store.claimRequested("target","other");
        store.sourceSynced(work);
        store.stage(work,store.pending(work,5).get(0),"[0,1]");
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET attempts=5 WHERE id=?",id);
        store.release(work,true);
        assertThat(store.status(id).get("status")).isEqualTo("FAILED");
        assertThat(store.claimRequested("target","other")).isNull();
        assertThat(store.requestBuild("target",2,"halfvec","LOCAL")).isEqualTo(id);
        var resumed = store.claimRequested("target","other");
        assertThat(resumed.sourceSynced()).isTrue();
        assertThat(store.pending(resumed,5)).isEmpty();
        assertThat(store.activate(resumed)).isTrue();
        assertThat(jdbc.queryForObject("SELECT mode FROM knowledge_embedding_settings WHERE id=1",String.class)).isEqualTo("LOCAL");
    }
    @Test void failedModeActivationRollsBackVectorsAndSelectedModeTogether() {
        store.requestBuild("semantic-target",2,"halfvec","SEMANTIC");
        var work=store.claimRequested("local-target","semantic-target");
        store.sourceSynced(work);
        store.stage(work,store.pending(work,5).get(0),"[1,0,0]");
        assertThatThrownBy(() -> store.activate(work)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT mode FROM knowledge_embedding_settings WHERE id=1",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("old");
    }
    @Test void lateWriterCannotReintroduceOldProviderAfterModeActivation() {
        store.requestBuild("local-target",2,"halfvec","LOCAL");
        var work=store.claimRequested("local-target","semantic-target");
        store.sourceSynced(work);
        store.stage(work,store.pending(work,5).get(0),"[0,1]");
        assertThat(store.activate(work)).isTrue();
        var persistence=new KnowledgeVectorPersistenceService(jdbc,
                new VectorCompressionService(new com.example.mealcheck.config.AppProperties()));
        var late=new KnowledgeVectorPersistenceService.KnowledgeVectorRow("late","late","late","general","[1,0]","old","hash",3,"manual",java.time.LocalDateTime.now());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> transaction.execute(status -> { persistence.add("admin",late); return null; }))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunks",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("local-target");
    }
    @Test void stagedVectorsSurviveRestartAndOnlyCompletedBuildsReplaceActiveData() {
        long id=store.request("new",2,"halfvec");
        var first=store.claim("new");
        var chunk=store.pending(first,5).get(0);
        assertThat(store.stage(first,chunk,"[0,1]")).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks",String.class)).isEqualTo("old");
        store.release(first,true);
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",id);
        var resumed=store.claim("new");
        assertThat(store.pending(resumed,5)).isEmpty();
        assertThat(store.activate(resumed)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM knowledge_chunks",String.class)).isEqualTo("[0,1]");
        assertThat(store.status(id).get("status")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_embedding_rebuild_items",Long.class)).isZero();
    }
    @Test void editedChunksAreRegeneratedBeforeActivationAndExpiredWorkersAreFenced() {
        long id=store.request("new",2,"vector");
        var old=store.claim("new");
        store.stage(old,store.pending(old,5).get(0),"[1,0]");
        jdbc.update("UPDATE knowledge_chunks SET content='edited' WHERE id=?",chunkId);
        assertThat(store.activate(old)).isFalse();
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",id);
        var current=store.claim("new");
        assertThat(store.activate(old)).isFalse();
        assertThat(store.stage(old,store.pending(current,5).get(0),"[1,0]")).isFalse();
        assertThat(store.stage(current,store.pending(current,5).get(0),"[0,1]")).isTrue();
        assertThat(store.activate(current)).isTrue();
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks",String.class)).isEqualTo("edited");
    }
    @Test void invalidActivationRollsBackAndFailedJobsRetainProgressForManualRetry() {
        long id=store.request("new",2,"halfvec");
        assertThat(store.request("new",2,"halfvec")).isEqualTo(id);
        assertThatThrownBy(() -> store.request("other",4,"halfvec")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var work=store.claim("new");
        store.stage(work,store.pending(work,5).get(0),"[1,0,0]");
        assertThatThrownBy(() -> store.activate(work)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT embedding_signature FROM knowledge_chunks",String.class)).isEqualTo("old");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='knowledge_chunks'::regclass AND attname='embedding_next' AND NOT attisdropped",Long.class)).isZero();
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET attempts=5 WHERE id=?",id);
        store.release(work,true);
        assertThat(store.status(id).get("status")).isEqualTo("FAILED");
        assertThat(store.request("new",2,"halfvec")).isEqualTo(id);
        store.retry(id,"new");
        assertThat(store.status(id).get("attempts")).isEqualTo(0);
        assertThat(((Number)store.status(id).get("staged")).longValue()).isEqualTo(1);
    }
    @Test void selectedRebuildUpdatesOnlyTargetsAndRetainsContentProviderAndOtherVectors() {
        String signature="hash-v1:3:vector-v1";
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature=? WHERE id=1",signature);
        jdbc.update("UPDATE knowledge_chunks SET embedding_signature=? WHERE id=?",signature,chunkId);
        long untouched=jdbc.queryForObject("INSERT INTO knowledge_chunks(source,title,content,embedding,embedding_signature) VALUES ('admin','keep','unchanged','[1,0,0]',?) RETURNING id",Long.class,signature);
        long revision=jdbc.queryForObject("SELECT revision FROM knowledge_cache_state WHERE id=1",Long.class);
        long id=store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of(chunkId));
        assertThat(store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of(chunkId))).isEqualTo(id);
        assertThatThrownBy(() -> store.requestBuild(signature,3,"vector","LOCAL")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var work=store.claim(signature);
        assertThat(work.sourceSynced()).isTrue();
        assertThat(store.pending(work,5)).extracting(KnowledgeRebuildStore.Chunk::id).containsExactly(chunkId);
        store.stage(work,store.pending(work,5).get(0),"[0,1,0]");
        assertThat(store.status(id).get("totalChunks")).isEqualTo(1L);
        assertThat(store.status(id).get("staged")).isEqualTo(1L);
        var inventory=store.inventory(signature);
        assertThat(inventory.get("counts")).isInstanceOf(java.util.Map.class);
        jdbc.update("UPDATE knowledge_embedding_rebuild_jobs SET attempts=5 WHERE id=?",id);
        store.release(work,true);
        assertThat(store.status(id).get("status")).isEqualTo("FAILED");
        assertThat(store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of(chunkId))).isEqualTo(id);
        work=store.claim(signature);
        assertThat(store.pending(work,5)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("[1,0,0]");
        assertThat(store.activate(work)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("[0,1,0]");
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM knowledge_chunks WHERE id=?",String.class,untouched)).isEqualTo("[1,0,0]");
        assertThat(jdbc.queryForObject("SELECT mode FROM knowledge_embedding_settings WHERE id=1",String.class)).isEqualTo("LOCAL");
        assertThat(jdbc.queryForObject("SELECT revision FROM knowledge_cache_state WHERE id=1",Long.class)).isGreaterThan(revision);
        assertThat(jdbc.queryForObject("SELECT embedding_updated_at IS NOT NULL FROM knowledge_chunks WHERE id=?",Boolean.class,chunkId)).isTrue();
        var generatedAt=jdbc.queryForObject("SELECT embedding_updated_at FROM knowledge_chunks WHERE id=?",java.time.OffsetDateTime.class,chunkId);
        assertThat(java.time.Duration.between(generatedAt.toInstant(),java.time.Instant.now()).abs()).isLessThan(java.time.Duration.ofSeconds(30));
        jdbc.update("DELETE FROM knowledge_chunks WHERE id=?",chunkId);
        assertThat(store.status(id).get("totalChunks")).isEqualTo(1L);
    }
    @Test void selectedRebuildRejectsMissingIdsIncompatibleStorageAndChangedActiveProfile() {
        String signature="hash-v1:3:vector-v1";
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature=? WHERE id=1",signature);
        assertThatThrownBy(() -> store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of(Long.MAX_VALUE)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> store.requestSelected(signature,2,"vector","LOCAL",java.util.List.of(chunkId)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        long id=store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of(chunkId));
        var work=store.claim(signature);
        store.stage(work,store.pending(work,5).get(0),"[0,1,0]");
        jdbc.update("UPDATE knowledge_embedding_settings SET signature='changed' WHERE id=1");
        assertThatThrownBy(() -> store.activate(work)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT embedding::text FROM knowledge_chunks WHERE id=?",String.class,chunkId)).isEqualTo("[1,0,0]");
        assertThat(store.status(id).get("status")).isEqualTo("RUNNING");
    }
    @Test @SuppressWarnings("unchecked") void inventoryUsesStoredSignaturesAndEditedStagingIsNotReportedReady() {
        String signature="hash-v1:3:vector-v1";
        jdbc.update("UPDATE knowledge_embedding_settings SET mode='LOCAL',signature=? WHERE id=1",signature);
        jdbc.update("UPDATE knowledge_chunks SET embedding_signature=? WHERE id=?",signature,chunkId);
        long id=store.requestSelected(signature,3,"vector","LOCAL",java.util.List.of(chunkId));
        var work=store.claim(signature);
        store.stage(work,store.pending(work,5).get(0),"[0,1,0]");
        var row=((java.util.List<java.util.Map<String,Object>>)store.inventory(signature).get("chunks")).get(0);
        assertThat(row.get("mode")).isEqualTo("LOCAL");
        assertThat(row.get("compatible")).isEqualTo(true);
        assertThat(row.get("staged")).isEqualTo(true);
        jdbc.update("UPDATE knowledge_chunks SET content='edited while generating' WHERE id=?",chunkId);
        assertThat(store.status(id).get("staged")).isEqualTo(0L);
        assertThat(store.activate(work)).isFalse();
        store.stage(work,store.pending(work,5).get(0),"[0,0,1]");
        assertThat(store.activate(work)).isTrue();
    }
}
