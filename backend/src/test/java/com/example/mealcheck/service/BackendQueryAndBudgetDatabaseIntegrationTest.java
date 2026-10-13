package com.example.mealcheck.service;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.*;
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
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({AiBudgetStore.class,AiCallPermitStore.class,AssistantConversationService.class})
@Testcontainers
class BackendQueryAndBudgetDatabaseIntegrationTest {
    @Test void backgroundCallsLeaveInteractivePermitsAndSettledRetentionIsBounded() {
        var background=permits.tryAcquire(3,60000,true);
        assertThat(background.permit()).isNotNull();
        assertThat(permits.tryAcquire(3,60000,true).reason()).isEqualTo("background");
        assertThat(permits.tryAcquire(3,60000,false).permit()).isNotNull();
        assertThat(permits.tryAcquire(3,60000,false).permit()).isNotNull();
        assertThat(permits.tryAcquire(3,60000,false).permit()).isNull();
        jdbc.update("INSERT INTO ai_budget_daily(quota_key,usage_day) VALUES ('old',CURRENT_DATE-40)");
        jdbc.update("INSERT INTO ai_budget_reservations(id,quota_key,usage_day,amount,status) SELECT md5(i::text)::uuid,'old',CURRENT_DATE-40,0,'SETTLED' FROM generate_series(1,1200) i");
        budgets.retain();assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_budget_reservations WHERE quota_key='old'",Long.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_budget_daily WHERE quota_key='old'",Long.class)).isEqualTo(1);
        budgets.retain();assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_budget_daily WHERE quota_key='old'",Long.class)).isZero();
    }
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AiBudgetStore budgets;
    @Autowired AiCallPermitStore permits;
    @Autowired AssistantConversationService conversations;
    @Autowired UserAccountRepository users;
    @Autowired MealRecordRepository meals;
    UserAccount user,other;
    final LocalDate day=LocalDate.of(2026,10,4);
    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(budgets,"globalDailyCalls",100000);
        jdbc.execute("TRUNCATE ai_call_permits");
        jdbc.execute("TRUNCATE ai_call_starts");
        org.springframework.test.util.ReflectionTestUtils.setField(permits,"maxStartsPerMinute",120);
        jdbc.execute("TRUNCATE ai_budget_daily,users CASCADE");
        user=create("query-user");other=create("other-user");
    }
    UserAccount create(String name) { var u=new UserAccount();u.setUsername(name);u.setPasswordHash("hash");return users.saveAndFlush(u); }
    String key() { return "user:"+user.getId(); }
    AiBudgetStore.Reservation reserve(BigDecimal limit,BigDecimal amount) { return budgets.reserve(key(),user.getId(),day,20,limit,amount,3600); }

    @Test void concurrentReservationsCannotExceedDailyCallLimit() throws Exception {
        var executor=Executors.newFixedThreadPool(8);
        var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for (int i=0;i<12;i++) results.add(executor.submit(()->{
                start.await();
                try { budgets.reserve(key(),user.getId(),day,3,BigDecimal.ZERO,new BigDecimal("0.02"),3600);return true; }
                catch (AiBudgetService.BudgetExceededException exceeded) { return false; }
            }));
            start.countDown();int allowed=0;for (var result:results) if (result.get(20,TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(3);
            assertThat(budgets.usage(key(),day).get("attempts")).isEqualTo(3);
            assertThat((BigDecimal)budgets.usage(key(),day).get("reserved")).isEqualByComparingTo("0.06");
        } finally { executor.shutdownNow(); }
    }
    @Test void monetaryAdmissionAndSettlementAreAtomicAndIdempotent() {
        var first=reserve(new BigDecimal("0.05"),new BigDecimal("0.03"));
        assertThatThrownBy(()->reserve(new BigDecimal("0.05"),new BigDecimal("0.03"))).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        budgets.dispatched(first);budgets.settle(first,new BigDecimal("0.01"));budgets.settle(first,new BigDecimal("0.01"));
        var second=reserve(new BigDecimal("0.05"),new BigDecimal("0.03"));
        budgets.settle(second,null); // Never dispatched: release both the call and monetary hold.
        var usage=budgets.usage(key(),day);
        assertThat(usage.get("attempts")).isEqualTo(1);
        assertThat((BigDecimal)usage.get("spent")).isEqualByComparingTo("0.01");
        assertThat((BigDecimal)usage.get("reserved")).isZero();
    }
    @Test void crashedDispatchedCallsRemainChargedWhileUnsentHoldsAreReleased() {
        var sent=reserve(BigDecimal.ZERO,new BigDecimal("0.02"));budgets.dispatched(sent);
        var unsent=reserve(BigDecimal.ZERO,new BigDecimal("0.02"));
        jdbc.update("UPDATE ai_budget_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '11 minutes'");
        budgets.recover(sent.id());budgets.recover(unsent.id());budgets.recover(sent.id());
        assertThat(budgets.usage(key(),day).get("attempts")).isEqualTo(1);
        assertThat((BigDecimal)budgets.usage(key(),day).get("spent")).isEqualByComparingTo("0.02");
        assertThat((BigDecimal)budgets.usage(key(),day).get("reserved")).isZero();
        users.delete(user);users.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_budget_reservations WHERE quota_key<>'global'",Long.class)).isZero();
        assertThat(budgets.usage("global",day).get("attempts")).isEqualTo(1);
    }
    @Test void dailyResetAndOtherUsersAreIndependent() {
        budgets.reserve(key(),user.getId(),day,1,BigDecimal.ZERO,new BigDecimal("0.02"),1);
        assertThatThrownBy(()->budgets.reserve(key(),user.getId(),day,1,BigDecimal.ZERO,new BigDecimal("0.02"),1)).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        assertThat(budgets.reserve(key(),user.getId(),day.plusDays(1),1,BigDecimal.ZERO,new BigDecimal("0.02"),1)).isNotNull();
        assertThat(budgets.reserve("user:"+other.getId(),other.getId(),day,1,BigDecimal.ZERO,new BigDecimal("0.02"),1)).isNotNull();
    }
    @Test void globalQuotaCombinesUsersAndSystemAndSettlementReleasesBothHolds() {
        org.springframework.test.util.ReflectionTestUtils.setField(budgets,"globalDailyCalls",2);
        var first=reserve(BigDecimal.ZERO,new BigDecimal("0.02"));
        var second=budgets.reserve("system",null,day,100,BigDecimal.ZERO,new BigDecimal("0.02"),1);
        assertThatThrownBy(()->budgets.reserve("user:"+other.getId(),other.getId(),day,100,BigDecimal.ZERO,new BigDecimal("0.02"),1))
                .isInstanceOf(AiBudgetService.BudgetExceededException.class);
        assertThat(budgets.usage("user:"+other.getId(),day).get("attempts")).isEqualTo(0);
        budgets.dispatched(first);budgets.settle(first,new BigDecimal("0.01"));
        budgets.settle(second,null);budgets.settle(second,null);
        assertThat(budgets.usage("global",day).get("attempts")).isEqualTo(1);
        assertThat((BigDecimal)budgets.usage("global",day).get("reserved")).isZero();
        assertThat((BigDecimal)budgets.usage("global",day).get("spent")).isEqualByComparingTo("0.01");
    }
    @Test void globalUndispatchedReservationRecoversAfterOwnerDeletion() {
        var hold=reserve(BigDecimal.ZERO,new BigDecimal("0.02"));
        users.delete(user);users.flush();
        jdbc.update("UPDATE ai_budget_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '11 minutes'");
        budgets.stale().forEach(budgets::recover);
        assertThat(budgets.usage("global",day).get("attempts")).isEqualTo(0);
        assertThat((BigDecimal)budgets.usage("global",day).get("reserved")).isZero();
    }
    @Test void independentlyRecoveredGlobalHoldCannotBeDispatchedLate() {
        var hold=reserve(BigDecimal.ZERO,new BigDecimal("0.02"));
        var globalId=jdbc.queryForObject("SELECT global_reservation_id FROM ai_budget_reservations WHERE id=?",UUID.class,hold.id());
        jdbc.update("UPDATE ai_budget_reservations SET created_at=CURRENT_TIMESTAMP-INTERVAL '11 minutes' WHERE id=?",globalId);
        budgets.recover(globalId);
        assertThatThrownBy(()->budgets.dispatched(hold)).isInstanceOf(IllegalStateException.class);
        budgets.settle(hold,null);
        assertThat(budgets.usage("global",day).get("attempts")).isEqualTo(0);
        assertThat(budgets.usage(key(),day).get("attempts")).isEqualTo(0);
    }
    @Test void permitsAreAtomicAcrossCallersAndExpiredCrashSlotsCanBeReused() throws Exception {
        var executor=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
        try {
            var results=new ArrayList<Future<UUID>>();
            for(int i=0;i<12;i++) results.add(executor.submit(()->{start.await();return permits.acquire(3,90000);}));
            start.countDown();var acquired=new ArrayList<UUID>();
            for(var result:results) {var id=result.get(30,TimeUnit.SECONDS);if(id!=null) acquired.add(id);}
            assertThat(acquired).hasSize(3);assertThat(permits.acquire(3,90000)).isNull();
            permits.release(acquired.get(0));permits.release(acquired.get(0));
            assertThat(permits.acquire(3,90000)).isNotNull();
            jdbc.update("UPDATE ai_call_permits SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
            assertThat(permits.acquire(3,90000)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_call_permits",Long.class)).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @Test void rollingMinuteLimitSurvivesPermitReleaseAndRefillsOnlyAfterExpiry() {
        org.springframework.test.util.ReflectionTestUtils.setField(permits,"maxStartsPerMinute",2);
        var first=permits.acquire(10,90000);permits.release(first);
        var second=permits.acquire(10,90000);permits.release(second);
        assertThat(permits.acquire(10,90000)).isNull();
        jdbc.update("UPDATE ai_call_starts SET started_at=CURRENT_TIMESTAMP-INTERVAL '61 seconds'");
        assertThat(permits.acquire(10,90000)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_call_starts",Long.class)).isEqualTo(1);
    }
    long conversation(UserAccount owner) {
        return jdbc.queryForObject("INSERT INTO assistant_conversations(user_id,title) VALUES (?,'test') RETURNING id",Long.class,owner.getId());
    }
    void message(long id,String text,LocalDateTime time) {
        jdbc.update("INSERT INTO assistant_conversation_messages(conversation_id,role,text,created_at) VALUES (?,'user',?,?)",id,text,time);
    }
    @Test void cursorHandlesEqualTimestampsNewMessagesAndConversationOwnership() {
        long id=conversation(user);var time=LocalDateTime.of(2026,10,4,12,0);
        for (int i=1;i<=5;i++) message(id,"message-"+i,time);
        var latest=conversations.detail(user,id,2,null);
        assertThat(latest.getMessages()).extracting(m->m.getText()).containsExactly("message-4","message-5");
        assertThat(latest.isHasMore()).isTrue();
        message(id,"new-message",time.plusSeconds(1));
        var middle=conversations.detail(user,id,2,latest.getNextBefore());
        assertThat(middle.getMessages()).extracting(m->m.getText()).containsExactly("message-2","message-3");
        var oldest=conversations.detail(user,id,2,middle.getNextBefore());
        assertThat(oldest.getMessages()).extracting(m->m.getText()).containsExactly("message-1");
        assertThat(oldest.isHasMore()).isFalse();assertThat(oldest.getNextBefore()).isNull();
        assertThatThrownBy(()->conversations.detail(other,id,2,null)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(()->conversations.detail(user,conversation(user),2,latest.getNextBefore())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->conversations.detail(user,id,101,null)).isInstanceOf(IllegalArgumentException.class);
    }
    void meal(UserAccount owner,int score,LocalDateTime time) {
        var record=new com.example.mealcheck.entity.MealRecord();record.setUser(owner);record.setScore(score);record.setCreatedAt(time.plusDays(20));record.setOccurredAt(time);
        record.setDetectedFoodsJson("[{\"name\":\"rice\"}]");record.setRiskTagsJson("[\"risk\"]");record.setAdvice("large advice".repeat(1000));
        meals.saveAndFlush(record);
    }
    @Test void sqlTrendAggregatesKeepBoundariesAndExcludeOtherUsers() {
        var since=LocalDateTime.of(2026,10,1,0,0);
        meal(user,0,since);meal(user,69,since.plusHours(1));meal(user,80,since.plusHours(1));meal(user,100,since.plusDays(1));meal(other,0,since.plusHours(2));
        var summary=meals.trendSummary(user.getId(),since);
        // Inclusive calendar start and actual meal time, regardless of creation time.
        assertThat(summary.getTotal()).isEqualTo(4);assertThat(summary.getLow()).isEqualTo(2);assertThat(summary.getHigh()).isEqualTo(2);
        assertThat(summary.getAverage()).isCloseTo(62.25,within(0.00001));
        assertThat(summary.getFirstScore()).isEqualTo(0);assertThat(summary.getLastScore()).isEqualTo(100);
        var daily=meals.trendDailyScores(user.getId(),since,ZoneId.systemDefault().getId());
        assertThat(daily).hasSize(2);assertThat(daily.get(0).getAverage()).isCloseTo(149.0/3,within(0.00001));
        assertThat(meals.trendFacts(user.getId(),since)).singleElement().satisfies(f->{
            assertThat(f.getFoods()).contains("rice");assertThat(f.getRisks()).contains("risk");
            assertThat(f.getOccurrences()).isEqualTo(4);
        });
        assertThat(meals.trendSummary(other.getId(),since.plusYears(1)).getTotal()).isZero();
        assertThat(meals.trendSummary(other.getId(),since.plusYears(1)).getAverage()).isNull();
    }
    @Test void repeatedTrendFactsAreWeightedAndKeepFirstMealOrder() {
        var since=LocalDateTime.of(2026,10,1,0,0);
        // Insert out of time order: ties in metric counts must follow first meal time, then id.
        jdbc.update("""
                INSERT INTO meal_records(user_id,created_at,score,detected_foods_json,risk_tags_json)
                VALUES (?,?,70,'[{"name":"later"}]','["risk-later"]'),
                       (?,?,70,'[{"name":"earlier"}]','["risk-earlier"]'),
                       (?,?,70,'[{"name":"tie"}]','[]')
                """,user.getId(),since.plusDays(1),user.getId(),since,user.getId(),since);
        jdbc.update("""
                INSERT INTO meal_records(user_id,created_at,score,detected_foods_json,risk_tags_json)
                SELECT ?,?,70,'[{"name":"earlier"}]','["risk-earlier"]' FROM generate_series(1,10000)
                """,user.getId(),since.plusDays(2));
        meal(other,70,since);meal(user,70,since.minusSeconds(1));
        var facts=meals.trendFacts(user.getId(),since);
        assertThat(facts).extracting(MealRecordRepository.TrendFacts::getFoods)
                .containsExactly("[{\"name\":\"earlier\"}]","[{\"name\":\"tie\"}]","[{\"name\":\"later\"}]");
        assertThat(facts).extracting(MealRecordRepository.TrendFacts::getOccurrences).containsExactly(10001L,1L,1L);
    }
    @Test void capturesQueryPlansWithLargeAdviceExcluded() throws Exception {
        jdbc.update("""
                INSERT INTO meal_records(user_id,score,created_at,detected_foods_json,risk_tags_json,advice)
                SELECT ?,70+(n%30),TIMESTAMP '2026-10-04'-n*INTERVAL '1 hour','[]','[]',repeat('advice',2000)
                FROM generate_series(1,2000) n
                """,user.getId());
        jdbc.execute("ANALYZE meal_records");
        var queries=new ArrayList<String>();
        queries.add("SELECT * FROM meal_records WHERE user_id=:userId AND created_at>:since");
        for (var method:MealRecordRepository.class.getDeclaredMethods()) {
            if (Set.of("trendSummary","trendDailyScores","trendFacts").contains(method.getName()) && method.isAnnotationPresent(org.springframework.data.jpa.repository.Query.class))
                queries.add(method.getAnnotation(org.springframework.data.jpa.repository.Query.class).value());
        }
        var params=Map.<String,Object>of("userId",user.getId(),"since",LocalDateTime.of(2026,9,4,0,0),"zone",ZoneId.systemDefault().getId(),"storageZone",com.example.mealcheck.util.AppTime.storageZone().getId());
        var namedJdbc=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var plans=new ArrayList<String>();
        for (String query:queries) {
            plans.add(query);plans.addAll(namedJdbc.query("EXPLAIN (ANALYZE,BUFFERS) "+query,params,(rs,n)->rs.getString(1)));
        }
        java.nio.file.Files.write(java.nio.file.Path.of("target","trend-query-plans.txt"),plans);
        assertThat(meals.trendSummary(user.getId(),LocalDateTime.of(2026,9,4,0,0)).getTotal()).isGreaterThan(0);
    }
}
