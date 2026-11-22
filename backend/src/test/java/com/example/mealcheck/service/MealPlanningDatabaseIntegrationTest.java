package com.example.mealcheck.service;

import com.example.mealcheck.repository.*;
import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({MealPlanningRepository.class,ActionPlanRepository.class,MealPlanningDatabaseIntegrationTest.JsonConfig.class})
@Testcontainers
class MealPlanningDatabaseIntegrationTest {
    @TestConfiguration static class JsonConfig { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MealPlanningRepository meals;
    @Autowired ActionPlanRepository plans;
    long user(String name) { return jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?,'hash') RETURNING id",Long.class,name); }
    long meal(long user, LocalDateTime created, LocalDateTime occurred, long revision) {
        return jdbc.queryForObject("""
                INSERT INTO meal_records(user_id,created_at,occurred_at,meal_type,score,detected_foods_json,original_foods_json,revision)
                VALUES (?,?,?,'LUNCH',80,'[{"name":"米饭","category":"staple","confidence":1}]',
                '[{"name":"候选食物","category":"other","confidence":0.3}]',?) RETURNING id
                """, Long.class,user,created,occurred,revision);
    }
    @Test void businessDayUsesEatingTimeWithLegacyFallbackAndNeverLeaksOtherUsersOrResolvedCandidates() {
        long owner=user("planning-owner"), other=user("planning-other");
        var day=AppTime.today(); var start=AppTime.startOfDay(day); var end=AppTime.startOfDay(day.plusDays(1));
        long first=meal(owner,start,start,0);
        meal(owner,start,start.minusNanos(1000),0);
        meal(owner,start,end,0);
        long corrected=meal(owner,start,start.plusHours(1),1);
        long legacy=meal(owner,start,null,0);
        meal(other,start,start,0);
        var selected=meals.today(owner,day);
        assertThat(selected).extracting(MealPlanningRepository.Meal::id).containsExactly(corrected,legacy,first);
        assertThat(selected.get(0).uncertainFoods()).isEmpty();
        assertThat(selected.get(1).uncertainFoods()).extracting(food -> food.getName()).containsExactly("候选食物");
        assertThat(selected.get(0).foods()).extracting(food -> food.getName()).containsExactly("米饭");
    }
    @Test void pendingQueryCountsOnlyOpenPlansBeforeApplyingPreviewLimitAndRecentQueryIsBounded() {
        long owner=user("planning-plans"), other=user("planning-plans-other"); var day=AppTime.today();
        for(int i=0;i<9;i++) jdbc.update("INSERT INTO action_plans(user_id,title,action_text,source_type,planned_date,status) VALUES (?,'plan','action','MANUAL',?,?)",owner,day,i<3?"DONE":"OPEN");
        jdbc.update("INSERT INTO action_plans(user_id,title,action_text,source_type,planned_date) VALUES (?,'other','other','MANUAL',?)",other,day);
        var pending=plans.pendingToday(owner,day);
        assertThat(pending.totalElements()).isEqualTo(6);
        assertThat(pending.content()).hasSize(5).allSatisfy(plan -> assertThat(plan.status()).isEqualTo("OPEN"));
        for(int i=0;i<12;i++) meal(owner,AppTime.startOfDay(day).plusMinutes(i),AppTime.startOfDay(day).plusMinutes(i),0);
        meal(owner,AppTime.startOfDay(day.minusDays(7)),AppTime.startOfDay(day.minusDays(7)),0);
        assertThat(meals.recent(owner,day)).hasSize(10);
    }
}
