package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
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
import static org.mockito.Mockito.*;
import java.util.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(AssistantMemoryStore.class)
@Testcontainers
class AssistantMemoryDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired AssistantMemoryStore store;
    @Autowired JdbcTemplate jdbc;
    long userId,conversationId,factId;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,assistant_memory_rebuild_jobs CASCADE");
        jdbc.update("UPDATE assistant_memory_version SET signature=NULL WHERE id=1");
        userId=jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES ('memory-user','hash') RETURNING id",Long.class);
        conversationId=jdbc.queryForObject("INSERT INTO assistant_conversations(user_id,title) VALUES (?,'memory') RETURNING id",Long.class,userId);
        factId=store.saveText(userId,conversationId,"restriction","我对花生过敏","a".repeat(64),0.9);
    }
    @Test void versionChangesResumeWithoutDeletingTextAndPreservePrecisionStorage() {
        long id=store.request("model-a:3:vector-v1",3,"vector");
        var first=store.claim("model-a:3:vector-v1");
        store.stage(first,store.pending(first,5).get(0),"[1,0,0]");store.release(first,true);
        jdbc.update("UPDATE assistant_memory_rebuild_jobs SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",id);
        var resumed=store.claim("model-a:3:vector-v1");assertThat(store.pending(resumed,5)).isEmpty();
        assertThat(store.activate(resumed)).isTrue();
        long next=store.request("model-b:2:halfvec-v1",2,"halfvec");
        var second=store.claim("model-b:2:halfvec-v1");
        assertThat(store.ready("model-a:3:vector-v1")).isTrue();
        assertThat(jdbc.queryForObject("SELECT fact_text FROM assistant_memory_facts",String.class)).isEqualTo("我对花生过敏");
        store.stage(second,store.pending(second,5).get(0),"[0,1]");assertThat(store.activate(second)).isTrue();
        assertThat(store.ready("model-b:2:halfvec-v1")).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_half::text FROM assistant_memory_vectors",String.class)).isEqualTo("[0,1]");
        assertThat(store.status(next).get("status")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT id FROM assistant_memory_facts",Long.class)).isEqualTo(factId);
    }
    @Test void newFactsBlockActivationAndExpiredWorkersCannotSwitchVersions() {
        long id=store.request("new",2,"vector");var expired=store.claim("new");
        store.stage(expired,store.pending(expired,5).get(0),"[1,0]");
        store.saveText(userId,conversationId,"goal","我的目标是增肌","b".repeat(64),0.8);
        assertThat(store.activate(expired)).isFalse();
        jdbc.update("UPDATE assistant_memory_rebuild_jobs SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",id);
        var replacement=store.claim("new");assertThat(store.claim("new")).isNull();
        assertThat(store.stage(expired,store.pending(replacement,5).get(0),"[1,0]")).isFalse();assertThat(store.activate(expired)).isFalse();
        store.stage(replacement,store.pending(replacement,5).get(0),"[0,1]");assertThat(store.activate(replacement)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_memory_vectors",Long.class)).isEqualTo(2);
        jdbc.update("DELETE FROM users WHERE id=?",userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_memory_vectors",Long.class)).isZero();
    }
    @Test void failedSwitchRollsBackAndManualRetryKeepsStagedWork() {
        long id=store.request("new",2,"vector");var work=store.claim("new");
        store.stage(work,store.pending(work,5).get(0),"[1,0,0]");
        assertThatThrownBy(()->store.activate(work)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.ready("new")).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_memory_facts",Long.class)).isEqualTo(1);
        jdbc.update("UPDATE assistant_memory_rebuild_jobs SET attempts=5 WHERE id=?",id);store.release(work,true);
        assertThat(store.status(id).get("status")).isEqualTo("FAILED");store.retry(id,"new");
        assertThat(store.status(id).get("staged")).isEqualTo(1L);
    }
    @Test void unavailableEmbeddingStillSavesTextAndFallbackIsScopedToUser() {
        var props=new AppProperties();props.getKnowledge().setEmbeddingDim(2);props.getKnowledge().setVectorStorage("vector");
        var embedding=mock(KnowledgeEmbeddingService.class);when(embedding.signature()).thenReturn("new");
        var memory=new AssistantLongTermMemoryService(jdbc,embedding,new VectorCompressionService(props),props,store);
        var user=new UserAccount();user.setId(userId);var conversation=new AssistantConversation();conversation.setId(conversationId);
        assertThat(memory.recall(user,conversation,"忌口",5)).extracting(AssistantLongTermMemoryService.MemoryFact::text).containsExactly("我对花生过敏");
        jdbc.update("UPDATE assistant_memory_version SET signature='new:vector-v1'");
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(memory.remember(user,conversation,"我不喜欢香菜")).isEqualTo(1);
        assertThat(memory.recall(user,conversation,"喜欢",5)).hasSize(2);
        var other=new UserAccount();other.setId(-1L);assertThat(memory.recall(other,conversation,"喜欢",5)).isEmpty();
    }
    @Test void flywayUpgradesLegacyHalfVectorsWithoutDiscardingText() {
        String name="legacy_memory_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+name);
        String url=POSTGRES.getJdbcUrl().replace("/"+POSTGRES.getDatabaseName(),"/"+name);
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword());
        var old=new JdbcTemplate(source);
        org.flywaydb.core.Flyway.configure().dataSource(source).target("21").load().migrate();
        old.execute("""
                CREATE TABLE assistant_memory_facts(id BIGSERIAL PRIMARY KEY,user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
                  conversation_id BIGINT REFERENCES assistant_conversations(id) ON DELETE CASCADE,fact_type VARCHAR(32) NOT NULL,
                  fact_text TEXT NOT NULL,fact_hash CHAR(64) NOT NULL,embedding halfvec(3) NOT NULL,embedding_signature VARCHAR(255) NOT NULL,
                  confidence DOUBLE PRECISION NOT NULL DEFAULT .7,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        old.execute("INSERT INTO users(username,password_hash) VALUES ('legacy','hash')");
        old.execute("INSERT INTO assistant_memory_facts(user_id,fact_type,fact_text,fact_hash,embedding,embedding_signature) VALUES (1,'restriction','我对花生过敏',repeat('a',64),'[1,0,0]','old:3')");
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        assertThat(old.queryForObject("SELECT fact_text FROM assistant_memory_facts",String.class)).isEqualTo("我对花生过敏");
        assertThat(old.queryForObject("SELECT embedding_half::text FROM assistant_memory_vectors",String.class)).isEqualTo("[1,0,0]");
        assertThat(old.queryForObject("SELECT signature FROM assistant_memory_version",String.class)).isEqualTo("old:3:halfvec-v1");
    }
}
