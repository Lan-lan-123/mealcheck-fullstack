package com.example.mealcheck.service;

import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MealAnalysisStatusExplanationTest {
    @Test void preparingQueuedAndRecoveringTasksExposeHonestStages() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new MealAnalysisRequestStore(jdbc, mock(UserAccountRepository.class), mock(UserGoalService.class),
                mock(RateLimitService.class), mock(MealRecordPersistenceService.class), new ObjectMapper(), mock(TaskFailureRecorder.class));
        var user = new UserAccount(); user.setId(1L); user.setUsername("tester");
        var principal = new UserPrincipal(user);
        when(jdbc.queryForList(anyString(),eq(1L),eq("key"))).thenReturn(List.of(Map.of(
                "status","PROCESSING","live",true,"has_input",false)));
        assertThat(store.status(principal,"key").stage()).isEqualTo("ACCEPTED");
        when(jdbc.queryForList(anyString(),eq(1L),eq("key"))).thenReturn(List.of(Map.of(
                "status","PENDING","live",false,"has_input",true)));
        assertThat(store.status(principal,"key").stage()).isEqualTo("QUEUED");
        when(jdbc.queryForList(anyString(),eq(1L),eq("key"))).thenReturn(List.of(Map.of(
                "status","PROCESSING","live",false,"has_input",true)));
        var recovering = store.status(principal,"key");
        assertThat(recovering.status()).isEqualTo("PENDING");
        assertThat(recovering.stage()).isEqualTo("QUEUED");
        assertThat(recovering.message()).contains("自动恢复");
        assertThat(recovering.retryAfterSeconds()).isEqualTo(3);
        when(jdbc.queryForList(anyString(),eq(1L),eq("key"))).thenReturn(List.of(Map.of(
                "status","PROCESSING","live",false,"has_input",false)));
        assertThat(store.status(principal,"key").status()).isEqualTo("RETRYABLE");
    }
    @Test void failedStatusOnlyPublishesSafeFailureCodesAndKeepsTheRequestIdentity() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new MealAnalysisRequestStore(jdbc, mock(UserAccountRepository.class), mock(UserGoalService.class),
                mock(RateLimitService.class), mock(MealRecordPersistenceService.class), new ObjectMapper(), mock(TaskFailureRecorder.class));
        var user = new UserAccount(); user.setId(1L); user.setUsername("tester");
        var principal = new UserPrincipal(user);
        for (String code : List.of("NON_FOOD", "IMAGE_UNCLEAR", "MODEL_SERVICE_ERROR", "MODEL_RESPONSE_INVALID", "MODEL_REQUEST_LIMITED", "REMOTE_CONFIGURATION", "NON_FOOD_LIMIT", "REMOTE_TIMEOUT", "AI_BUDGET_EXCEEDED")) {
            when(jdbc.queryForList(anyString(), eq(1L), eq("request-key")))
                    .thenReturn(List.of(Map.of("status", "FAILED", "last_error", code, "live", true)));
            var status = store.status(principal, "request-key");
            assertThat(status.key()).isEqualTo("request-key");
            assertThat(status.errorCode()).isEqualTo(code);
            assertThat(status.result()).isNull();
        }
        when(jdbc.queryForList(anyString(), eq(1L), eq("request-key")))
                .thenReturn(List.of(Map.of("status", "FAILED", "last_error", "private provider content", "live", true)));
        assertThat(store.status(principal, "request-key").errorCode()).isNull();
    }
}
