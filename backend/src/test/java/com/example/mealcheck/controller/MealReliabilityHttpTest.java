package com.example.mealcheck.controller;

import com.example.mealcheck.config.*;
import com.example.mealcheck.dto.MealAnalysisRequestResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.*;
import com.example.mealcheck.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({MealAnalysisRequestController.class,MealCorrectionController.class,MealCorrectionEvaluationController.class,VisionEvaluationController.class,TaskFailureAdminController.class})
@Import({SecurityConfig.class,JwtService.class,JwtAuthenticationFilter.class,CustomUserDetailsService.class})
@EnableConfigurationProperties(AppProperties.class)
class MealReliabilityHttpTest {
    @Test void failureTimeFiltersConvertBusinessTimeAndRejectMalformedInput() throws Exception {
        http.perform(get("/api/admin/task-failures").with(user(principal("ADMIN")))
                .param("from","2026-10-09T00:05").param("to","2026-10-09T01:05:00+08:00"))
                .andExpect(status().isOk());
        verify(failures).list(eq("ALL"),eq("OPEN"),isNull(),isNull(),
                eq(java.time.LocalDateTime.of(2026,10,8,16,5)),eq(java.time.LocalDateTime.of(2026,10,8,17,5)),eq(0),eq(20));
        http.perform(get("/api/admin/task-failures").with(user(principal("ADMIN"))).param("from","not-a-time"))
                .andExpect(status().isBadRequest());
    }
    @Autowired MockMvc http;
    @MockBean MealAnalysisRequestService requests;
    @MockBean MealCorrectionService corrections;
    @MockBean MealCorrectionEvaluationService evaluation;
    @MockBean ImageUploadValidationService validation;
    @MockBean VisionFoodRecognitionService vision;
    @MockBean AiChatClient ai;
    @MockBean TaskFailureManagementService failures;
    @MockBean UserAccountRepository users;
    @MockBean TokenBlacklistService blacklist;
    private UserPrincipal principal(String role) {
        var account=new UserAccount(); account.setId(1L); account.setUsername("alice"); account.setPasswordHash("hash"); account.setRole(role);
        return new UserPrincipal(account);
    }
    @Test void anonymousCannotReadResultsAndNormalUserCannotReviewCorrections() throws Exception {
        http.perform(get("/api/meals/analysis-requests/meal-request-key-0001")).andExpect(status().isUnauthorized());
        http.perform(get("/api/admin/meal-corrections").with(user(principal("USER")))).andExpect(status().isForbidden());
        verifyNoInteractions(requests,evaluation);
    }
    @Test void normalUsersCannotInvokePaidVisionEvaluation() throws Exception {
        http.perform(multipart("/api/admin/vision-evaluation/recognize").file(new MockMultipartFile("image","meal.jpg","image/jpeg",new byte[]{1})).with(user(principal("USER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(validation,vision,ai);
    }
    @Test void processingResultIsAcceptedAndExposesRetryAfter() throws Exception {
        when(requests.enqueue(any(),anyString(),any(),anyString())).thenReturn(new MealAnalysisRequestResponse("meal-request-key-0001","PENDING",null,3));
        http.perform(multipart("/api/meals/analysis-requests").file(new MockMultipartFile("image","meal.jpg","image/jpeg",new byte[]{1}))
                .header("Idempotency-Key","meal-request-key-0001").with(user(principal("USER"))))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After","3"));
    }
    @Test void correctionRejectsEmptyFoodsAndReviewRequiresSanitization() throws Exception {
        http.perform(post("/api/meals/1/corrections").with(user(principal("USER"))).contentType("application/json")
                .content("{\"expectedRevision\":0,\"foods\":[],\"reason\":\"missing food\"}"))
                .andExpect(status().isBadRequest());
        http.perform(put("/api/admin/meal-corrections/1/review").with(user(principal("ADMIN"))).contentType("application/json")
                .content("{\"status\":\"APPROVED\",\"sanitizedConfirmed\":false,\"foods\":[{\"name\":\"rice\",\"category\":\"staple\"}],\"expectedScore\":80}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(corrections,evaluation);
    }
    @Test void adminCanReadRegressionResults() throws Exception {
        when(evaluation.evaluate()).thenReturn(new MealCorrectionEvaluationService.Evaluation("v1","fingerprint","rules",0,0,false,java.util.List.of()));
        http.perform(get("/api/admin/meal-corrections/evaluation").with(user(principal("ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
    }
    @Test void failureManagementIsAdminOnlyIncludingRetryAndClose() throws Exception {
        http.perform(get("/api/admin/task-failures")).andExpect(status().isUnauthorized());
        http.perform(get("/api/admin/task-failures").with(user(principal("USER")))).andExpect(status().isForbidden());
        for (String action : java.util.List.of("retry","close")) http.perform(post("/api/admin/task-failures/1/"+action)
                .with(user(principal("USER"))).contentType("application/json")
                .content("{\"operationId\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":0,\"reason\":\"recovered\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(failures);
    }
    @Test void adminActionsRequireReasonAndOperationIdentity() throws Exception {
        http.perform(post("/api/admin/task-failures/1/retry").with(user(principal("ADMIN"))).contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(failures);
    }
}
