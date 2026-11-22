package com.example.mealcheck.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(AiModelAdmissionStore.class)
@Testcontainers
class AiModelAdmissionDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AiModelAdmissionStore store;
    @BeforeEach void reset() {jdbc.execute("TRUNCATE ai_model_admission_state,ai_model_admission_starts");}
    @Test void sharedConcurrencyAndModelIsolationAndExpiredCrashLeases() {
        var first=store.acquire("a",1,60000,0,30,60000);assertThat(first.id()).isNotNull();
        assertThat(store.acquire("a",1,60000,0,30,60000).reason()).isEqualTo("concurrency");
        assertThat(store.acquire("b",1,60000,0,30,60000).id()).isNotNull();
        jdbc.update("UPDATE ai_model_admission_starts SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",first.id());
        assertThat(store.acquire("a",1,60000,0,30,60000).id()).isNotNull();
    }
    @Test void rpmPacingTpmSettlementAndCooldownDoNotResetEachOther() {
        var first=store.acquire("a",10,1,100,80,60000);assertThat(first.id()).isNotNull();store.release(first.id(),20L);
        assertThat(store.acquire("a",10,1,100,80,60000).reason()).isEqualTo("rpm");
        assertThat(store.acquire("a",10,60000,100,81,60000).reason()).isEqualTo("tpm");
        assertThat(store.acquire("a",10,2,0,1,60000).reason()).isEqualTo("pacing");
        store.cooldown("a",120000);store.cooldown("a",1000);
        assertThat(store.acquire("a",10,60000,0,1,60000).retryMillis()).isGreaterThan(100000);
        jdbc.update("UPDATE ai_model_admission_state SET cooldown_until=clock_timestamp()-INTERVAL '1 second'");
        jdbc.update("UPDATE ai_model_admission_starts SET started_at=clock_timestamp()-INTERVAL '61 seconds'");
        assertThat(store.acquire("a",10,1,100,80,60000).id()).isNotNull();
    }
    @Test void fiftySimultaneousAttemptsCannotOverAdmit() throws Exception {
        var pool=Executors.newFixedThreadPool(50);var start=new CountDownLatch(1);
        try {
            var jobs=new ArrayList<Future<AiModelAdmissionStore.Admission>>();
            for(int i=0;i<50;i++)jobs.add(pool.submit(()->{start.await();return store.acquire("shared",10,60000,0,1,60000);}));
            start.countDown();var ids=new ArrayList<UUID>();
            for(var job:jobs) {var result=job.get(30,TimeUnit.SECONDS);if(result.id()!=null)ids.add(result.id());}
            assertThat(ids).isNotEmpty().hasSizeLessThanOrEqualTo(10);
            ids.forEach(id->store.release(id,null));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_model_admission_starts WHERE NOT released",Long.class)).isZero();
        } finally {pool.shutdownNow();}
    }
}
