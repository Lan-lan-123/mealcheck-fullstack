package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.controller.AssistantDeliveryController;
import com.example.mealcheck.dto.JournalDtos.*;
import com.example.mealcheck.dto.MealCorrectionRequest.CorrectedFood;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.*;
import com.example.mealcheck.util.AppTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"mealcheck.analysis.dispatch=database","mealcheck.analysis.worker-enabled=false",
    "mealcheck.mcp.enabled=false","mealcheck.knowledge.auto-index=false","mealcheck.ai.api-key=",
    "mealcheck.upload-dir=target/journal-test-uploads"})
@AutoConfigureMockMvc
@Testcontainers
class MealJournalDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @MockBean BackgroundTaskDispatcher background;
    @MockBean RateLimitService limits;
    @MockBean RedisCacheService redis;
    @MockBean AiChatClient ai;
    @MockBean TokenBlacklistService blacklist;
    @Autowired UserAccountRepository users;
    @Autowired MealRecordRepository meals;
    @Autowired MealJournalService journal;
    @Autowired BehaviorTrackingService tracking;
    @Autowired MealAnalysisService analysis;
    @Autowired WeeklyReportService reports;
    @Autowired AssistantDeliveryController delivery;
    @Autowired AssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @Autowired JwtService jwt;
    @Autowired WeeklyReportSnapshotRepository snapshots;
    @Autowired WeeklyReportHistoryService reportHistory;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.boot.test.mock.mockito.SpyBean TrackingQueryRepository trackingQueries;
    UserPrincipal alice,bob; LocalDate today;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        var a=new UserAccount();a.setUsername("journal-alice");a.setPasswordHash("hash");alice=new UserPrincipal(users.saveAndFlush(a));
        var b=new UserAccount();b.setUsername("journal-bob");b.setPasswordHash("hash");bob=new UserPrincipal(users.saveAndFlush(b));
        today=AppTime.today();
        when(limits.consumeAssistant(anyString())).thenReturn(new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.ALLOWED,20,19,Duration.ZERO));
    }
    private com.example.mealcheck.dto.MealRecordResponse manual(LocalDate date,String type) {
        return journal.create(alice,new ManualMeal(date+"T00:01:00+08:00",type,"current","补记",List.of(new CorrectedFood("青菜","vegetable"))));
    }
    @Test void manualBackdateUsesBusinessDayAndPreservesCreatedTimeWithoutCallingAi() {
        var saved=manual(today.minusDays(12),"BREAKFAST");
        assertThat(saved.getRecordSource()).isEqualTo("MANUAL");assertThat(saved.getImageUrl()).isNull();
        assertThat(saved.getCreatedAt()).isAfter(saved.getOccurredAt());assertThat(saved.isTimeConfirmed()).isTrue();
        assertThat(analysis.listPeriod(users.findById(alice.getId()).orElseThrow(),today.minusDays(12),today.minusDays(12))).hasSize(1);
        assertThat(analysis.trend(alice,7).getTotal()).isZero();
        assertThat(reports.latest(alice).getTracking().recordedDays()).isZero();
        assertThat(tracking.summary(alice.getId(),today.minusDays(12),today.minusDays(12)).daily().get(0).slots().get("BREAKFAST")).isEqualTo("RECORDED");
        assertThat(jdbc.queryForObject("SELECT last_upload_at IS NULL FROM users WHERE id=?",Boolean.class,alice.getId())).isTrue();
        verifyNoInteractions(ai);
    }
    @Test void metadataHasIndependentVersionOwnershipAndMovesReportingPeriod() {
        var saved=manual(today.minusDays(10),"LUNCH");
        journal.update(alice,saved.getId(),new Metadata(today+"T00:02:00+08:00","DINNER","修改",0));
        var updated=meals.findById(saved.getId()).orElseThrow();
        assertThat(updated.getRevision()).isZero();assertThat(updated.getMetadataVersion()).isEqualTo(1);
        assertThat(analysis.trend(alice,7).getTotal()).isEqualTo(1);
        assertThatThrownBy(()->journal.update(alice,saved.getId(),new Metadata(today+"T00:02","LUNCH",null,0))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(()->journal.update(bob,saved.getId(),new Metadata(today+"T00:02","LUNCH",null,1))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
    @Test void explicitSkipIsSeparateFromUnknownAndRecordingClearsOnlyMatchingSlot() {
        journal.skipped(alice,new SkipSlot(today,"BREAKFAST",null),false);
        var before=tracking.summary(alice.getId(),today,today);
        assertThat(before.confirmedSkippedSlots()).isEqualTo(1);assertThat(before.unknownSlots()).isEqualTo(2);assertThat(before.recordedDays()).isZero();
        manual(today,"LUNCH");assertThat(tracking.summary(alice.getId(),today,today).confirmedSkippedSlots()).isEqualTo(1);
        manual(today,"BREAKFAST");assertThat(tracking.summary(alice.getId(),today,today).confirmedSkippedSlots()).isZero();
        assertThatThrownBy(()->journal.skipped(alice,new SkipSlot(today,"BREAKFAST",null),false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->journal.skipped(alice,new SkipSlot(today.plusDays(1),"DINNER",null),false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void goalProgressHasWeeklyBoundariesAndDailyHistoryAndIgnoresNutrition() {
        LocalDate monday=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var goal=tracking.saveGoal(alice,null,new BehaviorGoal("RECORD_DAYS_WEEKLY",2,monday,monday.plusDays(6),true,0));
        manual(monday.minusDays(1),"LUNCH");manual(monday,"LUNCH");manual(monday,"LUNCH");manual(monday,"DINNER");
        var progress=tracking.summary(alice.getId(),monday,today).goals().get(0);
        assertThat(progress.completed()).isEqualTo(1);assertThat(progress.remaining()).isEqualTo(1);assertThat(progress.achieved()).isFalse();
        assertThatThrownBy(()->tracking.saveGoal(alice,null,new BehaviorGoal("RECORD_DAYS_WEEKLY",3,monday,monday.plusDays(6),true,0))).isInstanceOf(ResponseStatusException.class);
        tracking.saveGoal(alice,goal.id(),new BehaviorGoal(goal.kind(),goal.target(),goal.startDate(),goal.endDate(),false,goal.version()));
        tracking.saveGoal(alice,null,new BehaviorGoal("MEALS_DAILY",2,monday,today,true,0));
        var daily=tracking.summary(alice.getId(),monday,today).goals().get(0);
        assertThat(daily.daysAchieved()).isEqualTo(1);assertThat(daily.eligibleDays()).isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(monday,today)+1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_nutrition_snapshots",Long.class)).isZero();
    }
    @Test void plansLinkOwnedSuggestionsAndReportExecutionWithOptimisticVersion() {
        var meal=manual(today,"LUNCH");
        var plan=tracking.createPlan(alice,new Plan("多吃蔬菜","午餐加一份蔬菜","MEAL",meal.getId(),null,today));
        assertThatThrownBy(()->tracking.createPlan(bob,new Plan("外部建议","内容","MEAL",meal.getId(),null,today))).isInstanceOf(ResponseStatusException.class);
        var done=tracking.updatePlan(alice,plan.id(),new PlanUpdate(plan.title(),plan.actionText(),today,"DONE","已执行",0));
        assertThat(done.completedAt()).isNotNull();
        assertThat(reports.latest(alice).getTracking().plans().get("DONE")).isEqualTo(1);
        assertThatThrownBy(()->tracking.updatePlan(alice,plan.id(),new PlanUpdate(plan.title(),plan.actionText(),today,"SKIPPED",null,0))).isInstanceOf(ResponseStatusException.class);
        var skipped=tracking.updatePlan(alice,plan.id(),new PlanUpdate(plan.title(),plan.actionText(),today,"SKIPPED","临时调整",1));
        assertThat(skipped.completedAt()).isNull();assertThat(tracking.summary(alice.getId(),today,today).plans().get("SKIPPED")).isEqualTo(1);
        meals.deleteById(meal.getId());assertThat(tracking.planPage(alice.getId(),today,today,0,100).content().get(0).sourceRecordId()).isNull();
    }
    @Test void concurrentMetadataWritesPermitOnlyOneVersion() throws Exception {
        var record=manual(today,"LUNCH");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<Integer> edit=()->{ gate.await();try { journal.update(alice,record.getId(),new Metadata(today+"T00:01:00+08:00","DINNER",null,0));return 200; }catch(ResponseStatusException e){ return e.getStatusCode().value(); } };
        try { var one=pool.submit(edit);var two=pool.submit(edit);gate.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409); }finally{ pool.shutdownNow(); }
    }
    @Test void feedbackAndAssistantPlanRequireOwnedMessageAndCancelledAnswersCannotBecomePlans() {
        var conversation=conversations.resolve(users.findById(alice.getId()).orElseThrow(),null,"建议");
        long message=conversations.append(conversation,"assistant","增加蔬菜","fixture-request","COMPLETE");
        delivery.feedback(alice,message,new Feedback("UNHELPFUL","INCORRECT","食物分类有误"));
        assertThat(jdbc.queryForObject("SELECT request_id FROM assistant_answer_feedback WHERE message_id=?",String.class,message)).isEqualTo("fixture-request");
        assertThatThrownBy(()->delivery.feedback(bob,message,new Feedback("HELPFUL","GENERAL",null))).isInstanceOf(ResponseStatusException.class);
        tracking.createPlan(alice,new Plan("建议","增加蔬菜","ASSISTANT",null,message,today));
        long cancelled=conversations.append(conversation,"assistant","部分内容","cancel-request","CANCELLED");
        assertThatThrownBy(()->tracking.createPlan(alice,new Plan("未完成建议","内容","ASSISTANT",null,cancelled,today))).isInstanceOf(ResponseStatusException.class);
        assertThat(conversations.detail(users.findById(alice.getId()).orElseThrow(),conversation.getId()).getMessages()).anySatisfy(m->{assertThat(m.getRequestId()).isEqualTo("cancel-request");assertThat(m.getDeliveryStatus()).isEqualTo("CANCELLED");});
    }
    @Test void manualHttpValidationAndAuthorizationAndLocalStreamRoundTrip() throws Exception {
        http.perform(post("/api/meals/manual").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        String token=jwt.generateToken(alice);
        http.perform(post("/api/meals/manual").header("Authorization","Bearer "+token).contentType("application/json").content("{\"occurredAt\":\"invalid\",\"mealType\":\"BREAKFAST\",\"goal\":\"current\",\"foods\":[]}")).andExpect(status().isBadRequest());
        var response=http.perform(post("/api/assistant/stream").header("Authorization","Bearer "+token).contentType("application/json").content("{\"question\":\"午餐如何搭配\"}")).andExpect(request().asyncStarted()).andReturn();
        String body=http.perform(asyncDispatch(response)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:queued","event:meta","event:delta","event:complete","requestId","messageId");
        assertThat(body.indexOf("event:queued")).isLessThan(body.indexOf("event:meta"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assistant_conversation_messages WHERE role='assistant' AND request_id IS NOT NULL",Long.class)).isEqualTo(1);
        verifyNoInteractions(ai);
    }
    @Test void goalPaginationAndEditingBeyondTheFormerLimitUseOwnedIdLookup() throws Exception {
        long oldest=jdbc.queryForObject("INSERT INTO behavior_goals(user_id,kind,target,start_date,end_date,active) VALUES(?,'MEALS_DAILY',1,DATE '2000-01-01',DATE '2000-01-01',false) RETURNING id",Long.class,alice.getId());
        jdbc.update("INSERT INTO behavior_goals(user_id,kind,target,start_date,end_date,active) SELECT ?,'MEALS_DAILY',1,DATE '2010-01-01'+n,DATE '2010-01-01'+n,false FROM generate_series(1,120) n",alice.getId());
        var first=tracking.goalPage(alice.getId(),0,20);assertThat(first.totalElements()).isEqualTo(121);assertThat(first.totalPages()).isEqualTo(7);
        assertThat(tracking.goalPage(alice.getId(),6,20).content()).anySatisfy(g->assertThat(g.id()).isEqualTo(oldest));
        var edited=tracking.saveGoal(alice,oldest,new BehaviorGoal("MEALS_DAILY",2,LocalDate.of(2000,1,1),LocalDate.of(2000,1,1),false,0));
        assertThat(edited.version()).isEqualTo(1);assertThat(edited.target()).isEqualTo(2);
        assertThatThrownBy(()->tracking.saveGoal(bob,oldest,new BehaviorGoal("MEALS_DAILY",1,today,today,false,0))).isInstanceOf(ResponseStatusException.class);
        http.perform(get("/api/profile/behavior-goals?page=6&size=20").header("Authorization","Bearer "+jwt.generateToken(alice)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(121)).andExpect(jsonPath("$.content.length()").value(1));
        http.perform(get("/api/profile/behavior-goals?page=-1").header("Authorization","Bearer "+jwt.generateToken(alice))).andExpect(status().isBadRequest());
    }
    @Test void planPaginationReturnsAllRowsBeyondFiveHundredAndExactTotals() {
        jdbc.update("INSERT INTO action_plans(user_id,title,action_text,source_type,planned_date) SELECT ?,'plan '||n,'action','MANUAL',? FROM generate_series(1,601) n",alice.getId(),today);
        var page=tracking.planPage(alice.getId(),today,today,30,20);
        assertThat(page.totalElements()).isEqualTo(601);assertThat(page.totalPages()).isEqualTo(31);assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).title()).isEqualTo("plan 601");
        assertThat(tracking.planPage(bob.getId(),today,today,0,20).totalElements()).isZero();
    }
    @Test void statisticsAggregateMealsOnceAcrossMoreThanOneHundredGoals() {
        LocalDate from=today.minusDays(119);
        jdbc.update("INSERT INTO behavior_goals(user_id,kind,target,start_date,end_date,active) SELECT ?,'MEALS_DAILY',2,CAST(? AS date)+n,CAST(? AS date)+n,true FROM generate_series(0,119) n",alice.getId(),from,from);
        jdbc.update("INSERT INTO meal_records(user_id,goal,score,occurred_at,meal_type,detected_foods_json,category_counts_json,risk_tags_json) SELECT ?,'balanced',80,CAST(? AS timestamp)+n*INTERVAL '1 day',slot,'[]','{}','[]' FROM generate_series(0,119) n CROSS JOIN (VALUES('BREAKFAST'),('LUNCH'),('BREAKFAST')) types(slot)",alice.getId(),AppTime.startOfDay(from));
        clearInvocations(trackingQueries);
        var result=tracking.summary(alice.getId(),from,today);
        assertThat(result.totalMeals()).isEqualTo(360);assertThat(result.recordedDays()).isEqualTo(120);assertThat(result.goals()).hasSize(120);
        assertThat(result.goals()).allSatisfy(p->{assertThat(p.completed()).isEqualTo(2);assertThat(p.daysAchieved()).isEqualTo(1);});
        verify(trackingQueries,times(1)).meals(eq(alice.getId()),eq(from),eq(today));
    }
    @Test void reportsKeepSnapshotsAndInvalidateOnlyPeriodsWithChangedMealData() throws Exception {
        var current=snapshot(today);var older=snapshot(today.minusDays(30));
        var plan=tracking.createPlan(alice,new Plan("future","action","MANUAL",null,null,today.plusDays(40)));
        tracking.updatePlan(alice,plan.id(),new PlanUpdate(plan.title(),plan.actionText(),plan.plannedDate(),"OPEN","备注",0));
        tracking.saveGoal(alice,null,new BehaviorGoal("MEALS_DAILY",1,today,today,true,0));
        assertThat(snapshots.findById(current.getId()).orElseThrow().isStale()).isFalse();
        var meal=manual(today,"BREAKFAST");
        assertThat(snapshots.count()).isEqualTo(2);
        assertThat(snapshots.findById(current.getId()).orElseThrow().isStale()).isTrue();
        assertThat(snapshots.findById(older.getId()).orElseThrow().isStale()).isFalse();
        assertThat(reports.latest(alice).getPeriodEnd()).isEqualTo(today);
        assertThat(reports.latest(alice).getTotalMeals()).isEqualTo(1);
        var archived=reportHistory.owned(alice.getId(),current.getId());
        assertThat(archived.report().getReportText()).isEqualTo("immutable original narrative");assertThat(archived.snapshot().stale()).isTrue();
        assertThatThrownBy(()->reportHistory.owned(bob.getId(),current.getId())).isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE weekly_report_snapshots SET generated_at=NULL WHERE id=?",current.getId());
        assertThat(reportHistory.owned(alice.getId(),current.getId()).snapshot().generatedAt()).isNull();
        assertThat(reportHistory.page(alice.getId(),0,20).content()).hasSize(2);
        http.perform(get("/api/reports/weekly/history/"+current.getId()).header("Authorization","Bearer "+jwt.generateToken(alice)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.report.reportText").value("immutable original narrative"));
        var fresh=snapshot(today);
        journal.update(alice,meal.getId(),new Metadata(today+"T00:01:00+08:00","BREAKFAST","只改备注",0));
        assertThat(snapshots.findById(fresh.getId()).orElseThrow().isStale()).isFalse();
        journal.update(alice,meal.getId(),new Metadata(today.minusDays(30)+"T00:01:00+08:00","BREAKFAST","移动日期",1));
        assertThat(snapshots.findById(fresh.getId()).orElseThrow().isStale()).isTrue();
        assertThat(snapshots.findById(older.getId()).orElseThrow().isStale()).isTrue();
    }
    private WeeklyReportSnapshot snapshot(LocalDate end) throws Exception {
        var report=new com.example.mealcheck.dto.WeeklyReportResponse();report.setDays(7);report.setPeriodStart(end.minusDays(6));report.setPeriodEnd(end);report.setReportText("immutable original narrative");
        var snapshot=new WeeklyReportSnapshot();snapshot.setUser(users.findById(alice.getId()).orElseThrow());snapshot.setDays(7);snapshot.setPeriodStart(report.getPeriodStart());snapshot.setPeriodEnd(end);snapshot.setReportJson(mapper.writeValueAsString(report));
        return snapshots.saveAndFlush(snapshot);
    }
}
