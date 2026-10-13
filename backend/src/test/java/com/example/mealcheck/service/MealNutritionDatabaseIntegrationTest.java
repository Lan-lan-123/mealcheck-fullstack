package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.MealNutritionDtos.*;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({MealNutritionService.class, MealNutritionCalculator.class, WeeklyReportDatabaseIntegrationTest.Config.class})
@Testcontainers
class MealNutritionDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MealNutritionService nutrition;
    @Autowired MealRecordRepository meals;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private UserPrincipal owner;
    private UserPrincipal other;
    private long mealId;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        var alice = new UserAccount(); alice.setUsername("alice"); alice.setPasswordHash("hash"); alice = users.saveAndFlush(alice);
        var bob = new UserAccount(); bob.setUsername("bob"); bob.setPasswordHash("hash"); bob = users.saveAndFlush(bob);
        owner = new UserPrincipal(alice); other = new UserPrincipal(bob);
        var meal = new MealRecord(); meal.setUser(alice); meal.setGoal("balanced"); meal.setScore(80);
        meal.setDetectedFoodsJson(Jsons.toJson(mapper, List.of(new FoodItem("示例食物", "other", 1, null))));
        mealId = meals.saveAndFlush(meal).getId();
    }
    private Update enabled(long revision, long version) {
        return new Update(true, revision, version, List.of(new Input(0, new BigDecimal("150"),
                new Nutrients(new BigDecimal("200"), new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("20")), "测试标签")));
    }

    @Test void optInPersistsAndDisableClearsDataWithoutChangingFoodRevisionOrScore() {
        assertThat(nutrition.get(owner, mealId).status()).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_nutrition_snapshots", Long.class)).isZero();
        var saved = nutrition.update(owner, mealId, enabled(0, 0));
        assertThat(nutrition.get(owner, mealId)).isEqualTo(saved);
        assertThat(saved.totals().energyKcal()).isEqualByComparingTo("300");
        assertThat(meals.findById(mealId).orElseThrow().getRevision()).isZero();
        assertThat(meals.findById(mealId).orElseThrow().getScore()).isEqualTo(80);
        nutrition.update(owner, mealId, new Update(false, 0, 1, List.of()));
        var disabled = nutrition.get(owner, mealId);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.totals()).isNull();
        assertThat(disabled.items()).allSatisfy(item -> assertThat(item.grams()).isNull());
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM meal_nutrition_snapshots", String.class)).doesNotContain("测试标签", "150");
    }

    @Test void enforcesOwnershipAndRejectsOutdatedFoodAndNutritionVersions() {
        assertThatThrownBy(() -> nutrition.get(other, mealId)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> nutrition.update(other, mealId, enabled(0, 0))).isInstanceOf(ResponseStatusException.class);
        nutrition.update(owner, mealId, enabled(0, 0));
        assertThatThrownBy(() -> nutrition.update(owner, mealId, enabled(0, 0))).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        // Same revision change made by MealCorrectionService, inside its account lock.
        jdbc.update("UPDATE meal_records SET revision=revision+1,detected_foods_json=? WHERE id=?",
                Jsons.toJson(mapper, List.of(new FoodItem("纠正后的食物", "vegetable", 1, null))), mealId);
        var stale = nutrition.get(owner, mealId);
        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.totals()).isNull();
        assertThat(stale.items().get(0).name()).isEqualTo("纠正后的食物");
        assertThat(stale.items().get(0).grams()).isNull();
        assertThatThrownBy(() -> nutrition.update(owner, mealId, enabled(0, 1))).isInstanceOf(ResponseStatusException.class);
        assertThat(nutrition.update(owner, mealId, enabled(1, 1)).status()).isEqualTo("ESTIMATED");
    }

    @Test void concurrentUpdatesPermitOneWriterAndDeletionCascades() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Integer> write = () -> { start.await(); try { nutrition.update(owner, mealId, enabled(0, 0)); return 200; }
            catch (ResponseStatusException ex) { return ex.getStatusCode().value(); } };
        try {
            var one = pool.submit(write); var two = pool.submit(write); start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        meals.deleteById(mealId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_nutrition_snapshots", Long.class)).isZero();
    }

    @Test void accountDeletionAlsoCascadesNutritionSnapshots() {
        nutrition.update(owner, mealId, enabled(0, 0));
        users.deleteById(owner.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_nutrition_snapshots", Long.class)).isZero();
    }
}
