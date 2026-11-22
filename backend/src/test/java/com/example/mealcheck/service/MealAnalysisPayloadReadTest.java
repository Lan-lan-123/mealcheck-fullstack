package com.example.mealcheck.service;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MealAnalysisPayloadReadTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MealAnalysisRequestStore store = new MealAnalysisRequestStore(jdbc, mock(UserAccountRepository.class),
            mock(UserGoalService.class), mock(RateLimitService.class), mock(MealRecordPersistenceService.class),
            new ObjectMapper(), mock(TaskFailureRecorder.class));
    private final MealAnalysisRequestStore.Work work;
    MealAnalysisPayloadReadTest() {
        var account = new UserAccount(); account.setId(1L); account.setUsername("tester");
        work = new MealAnalysisRequestStore.Work(new UserPrincipal(account), "key", "token", "balanced",
                new ValidatedImage(new byte[0], "image/jpeg", ".jpg", 2, 3, "meal.jpg"));
    }
    @Test void payloadBytesAreLoadedSeparatelyAndMetadataIsPreserved() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq("key"), eq("token")))
                .thenReturn(List.of(new byte[]{1, 2, 3}));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), eq("key"), eq("token"), isNull())).thenReturn(1L);
        var image = store.loadImage(work);
        assertThat(work.imageMetadata().size()).isZero(); assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(image.width()).isEqualTo(2); assertThat(image.height()).isEqualTo(3);
        assertThat(image.originalFilename()).isEqualTo("meal.jpg");
    }
    @Test void disappearingOrExpiredPayloadReadStopsBeforeReturningBytes() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq("key"), eq("token"))).thenReturn(List.of());
        assertThatThrownBy(() -> store.loadImage(work)).isInstanceOf(MealAnalysisRequestStore.WorkNoLongerOwnedException.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq("key"), eq("token"))).thenReturn(List.of(new byte[]{1}));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), eq("key"), eq("token"), isNull())).thenReturn(0L);
        assertThatThrownBy(() -> store.loadImage(work)).isInstanceOf(MealAnalysisRequestStore.WorkNoLongerOwnedException.class);
    }
}
