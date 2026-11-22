package com.example.mealcheck.controller;

import com.example.mealcheck.config.*;
import com.example.mealcheck.dto.*;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.*;
import com.example.mealcheck.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({MealController.class, MealQueryController.class, WeeklyReportJobController.class, AdminController.class, AiBudgetController.class, AiBudgetMonitoringController.class, AssistantMemoryMaintenanceController.class, DietPreferenceController.class, AiSettingsController.class})
@Import({CsrfTestRequests.class,SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class, CustomUserDetailsService.class,
        MealAnalysisService.class, MealScoringService.class, ImageUploadValidationService.class,
        WeeklyReportRequestService.class})
@EnableConfigurationProperties(AppProperties.class)
class ApiSecurityAndMealFlowTest {
    @MockitoBean AiSettingsService aiSettings;
    @MockitoBean AiChatClient connectionClient;

    @Test void onlyAdministratorsCanReadChangeResetOrTestAiConnections() throws Exception {
        for(String path:List.of("/api/admin/ai-settings", "/api/admin/ai-settings/test", "/api/admin/ai-settings/reset")) {
            http.perform(get(path)).andExpect(status().isUnauthorized());
        }
        http.perform(get("/api/admin/ai-settings").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        http.perform(put("/api/admin/ai-settings").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"model\":\"test\",\"baseUrl\":\"https://provider.test/v1\",\"version\":0}"))
                .andExpect(status().isForbidden());
        for(String action:List.of("test","reset")) http.perform(post("/api/admin/ai-settings/"+action)
                .header("Authorization","Bearer "+token).contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(aiSettings,connectionClient);
        var manager=new UserAccount(); manager.setId(20L); manager.setUsername("settings-admin");
        manager.setPasswordHash("hash"); manager.setRole("ADMIN");
        when(users.findByUsername(manager.getUsername())).thenReturn(Optional.of(manager));
        when(users.findById(20L)).thenReturn(Optional.of(manager));
        String adminToken=jwt.generateToken(new UserPrincipal(manager));
        var view=new AiSettingsService.View("qwen3.7-flash","https://provider.test/v1",true,"ADMIN",1,null);
        when(aiSettings.view()).thenReturn(view);
        when(aiSettings.save(any(),any())).thenReturn(view);
        http.perform(get("/api/admin/ai-settings").header("Authorization","Bearer "+adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.model").value("qwen3.7-flash"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
        http.perform(put("/api/admin/ai-settings").header("Authorization","Bearer "+adminToken).contentType("application/json")
                .content("{\"model\":\"qwen3.7-flash\",\"baseUrl\":\"https://provider.test/v1\",\"apiKey\":\"private-key\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").doesNotExist());
        verify(aiSettings).save(argThat(r->r.apiKey().equals("private-key")),argThat(p->p.getId().equals(20L)));
        http.perform(put("/api/admin/ai-settings").header("Authorization","Bearer "+adminToken).contentType("application/json")
                .content("{\"model\":\"\",\"baseUrl\":\"\",\"version\":-1}"))
                .andExpect(status().isBadRequest());
        when(connectionClient.completeWith(anyString(),anyString(),any(),any(),any(),anyInt(),anyList(),anyDouble()))
                .thenThrow(new AiChatClient.AiClientException("PRIVATE_PROVIDER_PAYLOAD"));
        http.perform(post("/api/admin/ai-settings/test").header("Authorization","Bearer "+adminToken)
                .contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_PROVIDER_PAYLOAD"))));
        assertThat(AiCallContext.capture()).isNull();
    }
    @MockitoBean DietPreferenceService dietPreferences;

    @Test void dietSettingsUseOnlyAuthenticatedIdentityAndValidatePayloads() throws Exception {
        when(dietPreferences.get(10L)).thenReturn(DietPreferences.defaults());
        http.perform(get("/api/profile/diet-preferences?userId=999").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        verify(dietPreferences).get(10L);
        http.perform(get("/api/profile/diet-preferences")).andExpect(status().isUnauthorized());
        when(dietPreferences.save(eq(10L),any())).thenReturn(new DietPreferences(DietPreferences.Style.VEGAN,List.of("花生"),List.of(),1));
        http.perform(put("/api/profile/diet-preferences?userId=999").header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"style\":\"VEGAN\",\"avoidFoods\":[\"花生\"],\"dislikedFoods\":[],\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        verify(dietPreferences).save(eq(10L),any());
        http.perform(put("/api/profile/diet-preferences").header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"style\":\"INVALID\",\"avoidFoods\":[],\"dislikedFoods\":[],\"version\":0}"))
                .andExpect(status().isBadRequest());
    }
    @Autowired MockMvc http;
    @Autowired JwtService jwt;
    @Autowired AppProperties properties;
    @MockitoBean BehaviorTrackingService tracking;
    @MockitoBean UserMealQueryService mealQueries;
    @MockitoBean UserAccountRepository users;
    @MockitoBean MealRecordRepository meals;
    @MockitoBean WeeklyReportJobRepository jobs;
    @MockitoBean WeeklyReportSnapshotRepository snapshots;
    @MockitoBean TokenBlacklistService blacklist;
    @MockitoBean VisionFoodRecognitionService vision;
    @MockitoBean KnowledgeIndexService knowledge;
    @MockitoBean AdviceGenerationService advice;
    @MockitoBean ImageStorageService images;
    @MockitoBean NonFoodUploadGuardService uploadGuard;
    @MockitoBean UserGoalService goals;
    @MockitoBean MealRecordPersistenceService persistence;
    @MockitoBean MealAnalysisRequestService analysisRequests;
    @MockitoBean MealAnalysisRequestStore requestStore;
    @MockitoBean WeeklyReportService reports;
    @MockitoBean RateLimitService limits;
    @MockitoBean AiBudgetService budgets;
    @MockitoBean AssistantMemoryStore memoryStore;
    @MockitoBean AssistantMemoryMaintenanceService memoryMaintenance;
    @MockitoBean AdminService admin;
    @MockitoBean AdminAuditService audit;
    @MockitoBean AssistantBenchmarkService benchmark;
    @MockitoBean AssistantBenchmarkHistoryService history;
    @MockitoBean AssistantEvaluationReviewService reviews;
    @MockitoBean AssistantEvaluationCandidateService candidates;
    UserAccount user;
    String token;

    @Test void summaryReadsUseAuthenticatedOwnershipAndDetailsCannotExposeAnotherUsersMeal() throws Exception {
        when(mealQueries.page(10L, 0, 10, null, null, null, null, null))
                .thenReturn(new MealSummaryPageResponse(List.of(), 0, 0, 0, 10));
        http.perform(get("/api/meals/summaries?userId=20").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        verify(mealQueries).page(10L, 0, 10, null, null, null, null, null);
        http.perform(get("/api/meals/summaries")).andExpect(status().isUnauthorized());
        MealRecord record = new MealRecord(); record.setId(42L); record.setUser(user); record.setScore(88);
        record.setDetectedFoodsJson("[]"); record.setCategoryCountsJson("{}"); record.setRiskTagsJson("[]");
        record.setAdvice("完整餐食建议");
        when(meals.findById(42L)).thenReturn(Optional.of(record));
        http.perform(get("/api/meals/42").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.advice").value("完整餐食建议"));
        UserAccount other = new UserAccount(); other.setId(20L); record.setUser(other);
        http.perform(get("/api/meals/42").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.advice").doesNotExist());
    }

    @Test void cursorMealListingRequiresAdministratorAndPreservesCursorParameters() throws Exception {
        http.perform(get("/api/admin/meals/cursor").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        var manager = new UserAccount();
        manager.setId(20L); manager.setUsername("cursor-admin"); manager.setPasswordHash("hash"); manager.setRole("ADMIN");
        when(users.findByUsername("cursor-admin")).thenReturn(Optional.of(manager));
        when(users.findById(20L)).thenReturn(Optional.of(manager));
        String adminToken = jwt.generateToken(new UserPrincipal(manager));
        var time = java.time.LocalDateTime.of(2026, 10, 9, 12, 0);
        when(admin.mealsCursor(20, "alice", null, null, null, null, time, 42L))
                .thenReturn(new AdminMealQueryService.CursorPage(List.of(), false, null, null, 20));
        http.perform(get("/api/admin/meals/cursor").param("username", "alice")
                        .param("beforeTime", time.atZone(com.example.mealcheck.util.AppTime.storageZone())
                                .withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString())
                        .param("beforeId", "42")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.size").value(20));
        verify(admin).mealsCursor(20, "alice", null, null, null, null, time, 42L);
    }

    @Test void ordinaryUsersCannotReadBudgetMonitoringOrRunMemoryRebuilds() throws Exception {
        http.perform(get("/api/admin/ai-budget").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        http.perform(post("/api/admin/assistant-memory/rebuilds").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        verifyNoInteractions(memoryStore,memoryMaintenance);
    }

    @Test void budgetStatusUsesAuthenticatedUserAndIgnoresForeignUserParameters() throws Exception {
        when(budgets.usage(10L)).thenReturn(java.util.Map.of("remainingCalls",7));
        http.perform(get("/api/profile/ai-budget?userId=20").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingCalls").value(7));
        verify(budgets).usage(10L);verify(budgets,never()).usage(20L);
        http.perform(get("/api/profile/ai-budget")).andExpect(status().isUnauthorized());
    }
    @Test void aiBudgetAndDeadlineErrorsKeepTheirHttpStatusAndPreventPersistence() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var image=new MockMultipartFile("image","meal.png","image/png",bytes.toByteArray());
        when(vision.recognize(any())).thenAnswer(call->{
            assertThat(AiCallContext.capture().userId()).isEqualTo(10L);
            throw new AiBudgetService.BudgetExceededException(100,3600);
        });
        http.perform(multipart("/api/meals/analyze").file(image).header("Authorization","Bearer "+token))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","3600"))
                .andExpect(jsonPath("$.code").value("AI_BUDGET_EXCEEDED"));
        doThrow(new AiBudgetService.BudgetUnavailableException()).when(vision).recognize(any());
        http.perform(multipart("/api/meals/analyze").file(image).header("Authorization","Bearer "+token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_BUDGET_UNAVAILABLE"));
        doThrow(new RemoteCallGuard.RemoteCallDeadlineException("deadline")).when(vision).recognize(any());
        http.perform(multipart("/api/meals/analyze").file(image).header("Authorization","Bearer "+token))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("AI_DEADLINE_EXCEEDED"));
        verifyNoInteractions(persistence);assertThat(AiCallContext.capture()).isNull();
    }
    @Test void trendUsesAggregatesAndNarrowFactsWithoutLoadingMealEntities() throws Exception {
        var summary=mock(MealRecordRepository.TrendSummary.class);
        when(summary.getTotal()).thenReturn(3L);when(summary.getAverage()).thenReturn(74.5);
        when(summary.getScored()).thenReturn(3L);when(summary.getLow()).thenReturn(1L);when(summary.getHigh()).thenReturn(1L);
        when(summary.getFirstScore()).thenReturn(69);when(summary.getLastScore()).thenReturn(80);
        when(meals.trendSummary(eq(10L),any())).thenReturn(summary);
        var facts=mock(MealRecordRepository.TrendFacts.class);
        when(facts.getOccurrences()).thenReturn(3L);
        when(facts.getFoods()).thenReturn("[{\"name\":\"米饭\"},{\"name\":\"鸡蛋\"}]");when(facts.getRisks()).thenReturn("[\"高油\"]");
        when(meals.trendFacts(eq(10L),any())).thenReturn(List.of(facts));
        var daily=mock(MealRecordRepository.DailyScore.class);when(daily.getDay()).thenReturn(java.time.LocalDate.of(2026,10,4));when(daily.getAverage()).thenReturn(74.5);
        when(meals.trendDailyScores(eq(10L),any(),anyString())).thenReturn(List.of(daily));
        http.perform(get("/api/meals/trends?days=3").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.averageScore").value(75))
                .andExpect(jsonPath("$.dailyAverageScores[0].value").value(75))
                .andExpect(jsonPath("$.foodCounts[0].label").value("米饭"));
        verify(meals,never()).findByUserAndCreatedAtAfterOrderByCreatedAtAsc(any(),any());
    }

    @BeforeEach
    void account() {
        user = new UserAccount(); user.setId(10L); user.setUsername("alice"); user.setPasswordHash("hash");
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        token = jwt.generateToken(new UserPrincipal(user));
        when(limits.consumeMealAnalysis("alice")).thenReturn(allowed());
        when(limits.consumeWeeklyReport("10")).thenReturn(allowed());
    }

    @Test
    void authenticationDependenciesReturn503WithoutExecutingBusinessOperations() throws Exception {
        when(blacklist.isBlacklisted(jwt.extractIdentity(token).tokenId())).thenThrow(new IllegalStateException("Redis unavailable"));
        http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_DEPENDENCY_UNAVAILABLE"))
                .andExpect(header().string("Retry-After", "3"));
        doReturn(false).when(blacklist).isBlacklisted(jwt.extractIdentity(token).tokenId());
        when(users.findById(10L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB unavailable"));
        http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(reports);
    }

    @Test
    void deletedAndRecreatedUsernameCannotReactivateAnOldToken() throws Exception {
        var replacement = new UserAccount(); replacement.setId(11L); replacement.setUsername("alice");
        replacement.setPasswordHash("new-hash");
        when(users.findById(10L)).thenReturn(Optional.empty());
        when(users.findById(11L)).thenReturn(Optional.of(replacement));
        when(users.findByUsername("alice")).thenReturn(Optional.of(replacement));
        http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
        String fresh = jwt.generateToken(new UserPrincipal(replacement));
        http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk());
        verify(reports).latest(argThat((UserPrincipal principal) -> principal.getId().equals(11L)));
    }
    @Test
    void legacyAnalysisEndpointHonorsTheIdempotencyHeader() throws Exception {
        var result=new MealAnalysisResponse(); result.setRecordId(99L);
        when(analysisRequests.submit(any(),eq("meal-request-key-0001"),any(),eq("current")))
                .thenReturn(new MealAnalysisRequestResponse("meal-request-key-0001","SUCCEEDED",result,0));
        http.perform(multipart("/api/meals/analyze").file(new MockMultipartFile("image","meal.jpg","image/jpeg",new byte[]{1}))
                .header("Authorization","Bearer " + token).header("Idempotency-Key","meal-request-key-0001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordId").value(99));
        verifyNoInteractions(vision);
        verify(limits,never()).consumeMealAnalysis(anyString());
    }
    @Test void legacyWithoutKeyCannotBypassUserOutstandingLimit() throws Exception {
        when(requestStore.reserveLegacy(any())).thenThrow(new AnalysisAdmissionException("ANALYSIS_USER_BUSY","busy"));
        http.perform(multipart("/api/meals/analyze").file(new MockMultipartFile("image","meal.jpg","image/jpeg",new byte[]{1}))
                .header("Authorization","Bearer "+token))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","3"))
                .andExpect(jsonPath("$.code").value("ANALYSIS_USER_BUSY"));
        verifyNoInteractions(vision,limits);
    }

    @Test
    void expiredMissingAndRevokedTokensAreUnauthorized() throws Exception {
        http.perform(get("/api/reports/weekly/latest")).andExpect(status().isUnauthorized());
        long old = properties.getJwt().getExpirationMinutes();
        String expired;
        try {
            properties.getJwt().setExpirationMinutes(-1);
            expired = jwt.generateToken(new UserPrincipal(user));
        } finally { properties.getJwt().setExpirationMinutes(old); }
        http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        when(blacklist.isBlacklisted(jwt.extractIdentity(token).tokenId())).thenReturn(true);
        http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }

    @Test
    void signedTokensWithoutImmutableIdentityOrExpirationAreRejected() throws Exception {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                properties.getJwt().getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String missingId = io.jsonwebtoken.Jwts.builder().id(java.util.UUID.randomUUID().toString()).claim("ver", 0L).subject("alice")
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(60))).signWith(key).compact();
        String missingExpiry = io.jsonwebtoken.Jwts.builder().id(java.util.UUID.randomUUID().toString()).claim("ver", 0L).subject("alice").claim("uid", 10L)
                .signWith(key).compact();
        String wrongSubject = io.jsonwebtoken.Jwts.builder().id(java.util.UUID.randomUUID().toString()).claim("ver", 0L).subject("bob").claim("uid", 10L)
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(60))).signWith(key).compact();
        for (String invalid : List.of(missingId, missingExpiry, wrongSubject, token + "broken")) {
            http.perform(get("/api/reports/weekly/latest").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(reports);
    }

    @Test
    void normalUserCannotCallAdminOrReadAnotherUsersImageOrDeleteTheirMeal() throws Exception {
        http.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        var other = new UserAccount(); other.setId(20L);
        var meal = new MealRecord(); meal.setUser(other); meal.setStoredImagePath("private.jpg");
        when(meals.findById(8L)).thenReturn(Optional.of(meal));
        http.perform(get("/api/meals/8/image").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        http.perform(delete("/api/meals/8").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(admin, images, persistence);
    }

    @Test
    void accountStatusChangesRequireAnAdministratorAndAnExplicitBoolean() throws Exception {
        String path = "/api/admin/users/10/status";
        http.perform(put(path).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        http.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(admin);
        var manager = new UserAccount(); manager.setId(20L); manager.setUsername("manager");
        manager.setPasswordHash("hash"); manager.setRole("ADMIN");
        when(users.findById(20L)).thenReturn(Optional.of(manager));
        String adminToken = jwt.generateToken(new UserPrincipal(manager));
        for (String payload : List.of("{}", "{\"enabled\":null}")) {
            http.perform(put(path).header("Authorization", "Bearer " + adminToken)
                    .contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(admin);
        var response = new AdminUserResponse(10L, "alice", "Alice", "USER", user.getCreatedAt(), null);
        response.setEnabled(false);
        when(admin.setUserEnabled(eq(10L), eq(false), any())).thenReturn(response);
        http.perform(put(path).header("Authorization", "Bearer " + adminToken)
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        verify(admin).setUserEnabled(eq(10L), eq(false), argThat(p -> p.getId().equals(20L)));
    }

    @Test
    void uploadsAreValidatedScoredAndPassedToTransactionalPersistence() throws Exception {
        var food = new RecognitionResult();
        food.setFoods(List.of(new FoodItem("米饭", "staple", .95, ""),
                new FoodItem("鸡蛋", "protein", .95, ""), new FoodItem("西兰花", "vegetable", .95, "")));
        food.setSceneSummary("no fried food and no sweet drink");
        when(vision.recognize(any())).thenReturn(food);
        when(goals.effectiveGoal(user, "current")).thenReturn("balanced");
        when(knowledge.search(anyString(), anyInt())).thenReturn(List.of());
        when(advice.generateResult(any(), any(), anyList(), anyString())).thenReturn(new AdviceGenerationService.Result("保持均衡搭配","MODEL",null));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var file = new MockMultipartFile("image", "meal.png", "image/png", bytes.toByteArray());
        http.perform(multipart("/api/meals/analyze").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evaluation.score").value(90));
        verify(persistence).saveWithImage(eq(user), argThat(record -> record.getScore() == 90),
                argThat(image -> image.contentType().equals("image/jpeg") && image.bytes().length > 0));
        http.perform(multipart("/api/meals/analyze")
                .file(new MockMultipartFile("image", "fake.jpg", "image/jpeg", "not an image".getBytes()))
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        verify(vision, times(1)).recognize(any());
    }

    @Test
    void submitsAndReusesJobsWithoutInvokingModelsAndRejectsForeignStatusReads() throws Exception {
        var job = mock(WeeklyReportJob.class);
        when(job.getId()).thenReturn(5L); when(job.getStatus()).thenReturn("PENDING"); when(job.getDays()).thenReturn(7);
        when(jobs.findByUserIdAndReportWeekAndDays(eq(10L), any(), eq(7)))
                .thenReturn(Optional.empty(), Optional.of(job), Optional.of(job));
        http.perform(post("/api/reports/weekly/jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/reports/weekly/jobs/5"))
                .andExpect(jsonPath("$.id").value(5));
        http.perform(post("/api/reports/weekly/jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(5));
        verify(limits, times(1)).consumeWeeklyReport("10");
        verify(jobs, times(1)).enqueue(eq(10L), any(), eq(7), any());
        http.perform(get("/api/reports/weekly/jobs/99").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        verifyNoInteractions(reports);
    }

    @Test
    void jobQuotaAndDependencyFailuresCannotEnqueueWork() throws Exception {
        when(limits.consumeWeeklyReport("10")).thenReturn(new RateLimitService.RateLimitDecision(
                RateLimitService.RateLimitStatus.LIMIT_EXCEEDED, 3, 0, Duration.ofSeconds(60)));
        http.perform(post("/api/reports/weekly/jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        when(limits.consumeWeeklyReport("10")).thenReturn(new RateLimitService.RateLimitDecision(
                RateLimitService.RateLimitStatus.REDIS_UNAVAILABLE, 3, 0, Duration.ZERO));
        http.perform(post("/api/reports/weekly/jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable());
        http.perform(post("/api/reports/weekly/jobs?days=31").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        verify(jobs, never()).enqueue(anyLong(), any(), anyInt(), any());
    }

    private RateLimitService.RateLimitDecision allowed() {
        return new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.ALLOWED, 3, 2, Duration.ZERO);
    }
}
