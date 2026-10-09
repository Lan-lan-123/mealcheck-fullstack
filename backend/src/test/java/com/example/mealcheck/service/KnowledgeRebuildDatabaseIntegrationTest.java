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
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE knowledge_embedding_rebuild_jobs,knowledge_chunks CASCADE");
        jdbc.execute("DROP INDEX IF EXISTS idx_knowledge_chunks_embedding");
        jdbc.execute("ALTER TABLE knowledge_chunks ALTER COLUMN embedding TYPE vector(3) USING '[1,0,0]'::vector(3)");
        chunkId=jdbc.queryForObject("INSERT INTO knowledge_chunks(source,title,content,embedding,embedding_signature) VALUES ('manual','title','original','[1,0,0]','old') RETURNING id", Long.class);
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
}
