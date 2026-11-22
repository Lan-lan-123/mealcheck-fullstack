package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.baseline-on-migrate=false","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(AppProperties.class)
@Import({AiCostSettingsService.class,AiBudgetStore.class,ModelPriceCatalog.class,ObjectMapper.class})
@Testcontainers
class AiCostSettingsDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired AiCostSettingsService settings;
    @Autowired AiBudgetStore budgets;
    @Autowired ModelPriceCatalog catalog;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @MockBean AdminAuditService audit;
    UserAccount user,other;
    final LocalDate day=LocalDate.of(2026,10,25);
    static BigDecimal amount(String value) { return new BigDecimal(value); }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users,ai_budget_daily CASCADE");
        jdbc.update("UPDATE ai_cost_settings SET version=0,global_daily_cost=NULL,user_daily_cost=NULL,system_daily_cost=NULL,price_catalog_json=NULL");
        properties.getAiBudget().setDailyCost(BigDecimal.ZERO);
        properties.getAiBudget().setSystemDailyCost(BigDecimal.ZERO);
        properties.getAi().setInputCostPerMillion(BigDecimal.ZERO);
        properties.getAi().setOutputCostPerMillion(BigDecimal.ZERO);
        user=create("cost-user");other=create("cost-other");
    }
    UserAccount create(String name) {
        var account=new UserAccount();account.setUsername(name);account.setPasswordHash("hash");return users.saveAndFlush(account);
    }
    AiCostSettingsService.Update update(long version,String global,String personal,String system,List<ModelPriceCatalog.Price> prices) {
        return new AiCostSettingsService.Update(version,amount(global),amount(personal),amount(system),prices);
    }
    AiBudgetStore.Reservation reserve(Long userId,String value) {
        return budgets.reserve(userId==null?"system":"user:"+userId,userId,day,100,BigDecimal.ZERO,amount(value),3600);
    }
    @Test void defaultsAreOptionalAndPersonalLimitsCannotBypassAdministratorLimits() {
        assertThat(settings.view().source()).isEqualTo("ENVIRONMENT");
        assertThat(settings.personalView(user.getId()).effectiveDailyCost()).isZero();
        settings.save(update(0,"0","0.05","0.03",List.of()),null);
        settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(0,amount("100")));
        assertThat(settings.personalView(user.getId()).effectiveDailyCost()).isEqualByComparingTo("0.05");
        reserve(user.getId(),"0.03");
        assertThatThrownBy(()->reserve(user.getId(),"0.03")).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(1,BigDecimal.ZERO));
        assertThat(settings.personalView(user.getId()).effectiveDailyCost()).isEqualByComparingTo("0.05");
        reserve(other.getId(),"0.03");
        reserve(null,"0.02");
        assertThatThrownBy(()->reserve(null,"0.02")).isInstanceOf(AiBudgetService.BudgetExceededException.class);
    }
    @Test void tighterPersonalLimitsAreIsolatedAndChangesNeverResetUsage() {
        settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(0,amount("0.03")));
        reserve(user.getId(),"0.02");
        reserve(other.getId(),"0.02");
        assertThatThrownBy(()->reserve(user.getId(),"0.02")).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(1,amount("0.01")));
        assertThatThrownBy(()->reserve(user.getId(),"0.001")).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(2,BigDecimal.ZERO));
        assertThat(budgets.usage("user:"+user.getId(),day)).containsEntry("attempts",1);
        assertThat((BigDecimal)budgets.usage("user:"+user.getId(),day).get("reserved")).isEqualByComparingTo("0.02");
        reserve(user.getId(),"0.02");
    }
    @Test void globalLimitIsSharedAndFailedAdmissionsRollbackBothLedgers() {
        settings.save(update(0,"0.04","0","0",List.of()),null);
        reserve(user.getId(),"0.03");
        assertThatThrownBy(()->reserve(other.getId(),"0.02")).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        assertThat(budgets.usage("user:"+other.getId(),day)).containsEntry("attempts",0);
        settings.reset(new AiCostSettingsService.Version(1),null);
        assertThat(budgets.globalUsage(day).get("costLimit")).isEqualTo(BigDecimal.ZERO);
        assertThat(budgets.globalUsage(day)).containsEntry("attempts",1);
        reserve(other.getId(),"0.02");
    }
    @Test void concurrentAdmissionsAcrossInstancesCannotExceedTheSavedGlobalLimit() throws Exception {
        settings.save(update(0,"0.06","0","0",List.of()),null);
        var replica=new AiBudgetStore(jdbc);
        ReflectionTestUtils.setField(replica,"costSettings",settings);
        var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            var transactions=new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            for(int i=0;i<12;i++) {
                final int index=i;
                futures.add(pool.submit(()->{
                    start.await();
                    try {
                        if(index%2==0)reserve(user.getId(),"0.02");
                        else transactions.execute(status->replica.reserve("user:"+other.getId(),other.getId(),day,100,BigDecimal.ZERO,amount("0.02"),3600));
                        return true;
                    } catch(AiBudgetService.BudgetExceededException exhausted) { return false; }
                }));
            }
            start.countDown();int accepted=0;
            for(var future:futures)if(future.get(20,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat((BigDecimal)budgets.globalUsage(day).get("reserved")).isEqualByComparingTo("0.06");
        } finally { pool.shutdownNow(); }
    }
    @Test void pricesRefreshAcrossInstancesAndRemovingAnEntryDoesNotRestoreAnOldLegacyPrice() {
        properties.getAi().setBaseUrl("https://provider.test/v1");properties.getAi().setModel("cost-model");
        properties.getAi().setInputCostPerMillion(amount("2"));
        var endpoint=URI.create("https://provider.test/v1/chat/completions");
        var replica=new ModelPriceCatalog(properties,new ObjectMapper(),"CNY","[]");
        ReflectionTestUtils.setField(replica,"costSettings",settings);
        assertThat(replica.lookup(endpoint,"cost-model").orElseThrow().inputCostPerMillion()).isEqualByComparingTo("2");
        var price=new ModelPriceCatalog.Price(endpoint.toString(),"cost-model",amount("4"),amount("8"),"CNY");
        settings.save(update(0,"0","0","0",List.of(price)),null);
        assertThat(replica.lookup(endpoint,"cost-model").orElseThrow().inputCostPerMillion()).isEqualByComparingTo("4");
        assertThat(catalog.lookup(endpoint,"cost-model").orElseThrow().outputCostPerMillion()).isEqualByComparingTo("8");
        settings.save(update(1,"0","0","0",List.of()),null);
        assertThat(replica.lookup(endpoint,"cost-model")).isEmpty();
        settings.reset(new AiCostSettingsService.Version(2),null);
        assertThat(replica.lookup(endpoint,"cost-model").orElseThrow().inputCostPerMillion()).isEqualByComparingTo("2");
    }
    @Test void staleVersionsAreRejectedAndPersonalSettingsAreDeletedWithTheAccount() {
        settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(0,amount("1")));
        assertThatThrownBy(()->settings.savePersonal(user.getId(),new AiCostSettingsService.PersonalUpdate(0,amount("2"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        settings.save(update(0,"2","0","0",List.of()),null);
        assertThatThrownBy(()->settings.reset(new AiCostSettingsService.Version(0),null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(settings.view().globalDailyCost()).isEqualByComparingTo("2");
        users.delete(user);users.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_personal_cost_settings",Integer.class)).isZero();
    }
    @Test void policyEditsWaitForActiveAdmissionsAndThenApplyWithoutErasingExistingHolds() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var admitted=new CountDownLatch(1);var release=new CountDownLatch(1);var editing=new CountDownLatch(1);
        try {
            var replica=new AiBudgetStore(jdbc);
            ReflectionTestUtils.setField(replica,"costSettings",settings);
            var transaction=new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            var admission=pool.submit(()->transaction.execute(status->{
                replica.reserve("user:"+user.getId(),user.getId(),day,100,BigDecimal.ZERO,amount("0.02"),3600);
                // Keep the real admission transaction open until the policy edit starts.
                admitted.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Admission release timed out"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IllegalStateException(interrupted); }
                return true;
            }));
            assertThat(admitted.await(5,TimeUnit.SECONDS)).isTrue();
            var edit=pool.submit(()->{editing.countDown();return settings.save(update(0,"0.01","0","0",List.of()),null);});
            assertThat(editing.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->edit.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();admission.get(5,TimeUnit.SECONDS);edit.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->reserve(other.getId(),"0.001")).isInstanceOf(AiBudgetService.BudgetExceededException.class);
            assertThat((BigDecimal)budgets.globalUsage(day).get("reserved")).isEqualByComparingTo("0.02");
        } finally { release.countDown();pool.shutdownNow(); }
    }
}
