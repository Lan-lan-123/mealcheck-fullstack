package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.NonFoodUploadAlert;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.NonFoodUploadEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NonFoodUploadGuardServiceTest {

    @Test
    void registerRaisesAlertWhenThresholdIsReached() {
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        AppProperties properties = new AppProperties();
        properties.getUploadGuard().setNonFoodWindowMinutes(5);
        properties.getUploadGuard().setNonFoodLimit(10);
        properties.getUploadGuard().setNonFoodBlockMinutes(60);
        NonFoodUploadEventRepository eventRepository = mock(NonFoodUploadEventRepository.class);
        NonFoodUploadGuardService service = new NonFoodUploadGuardService(redisCacheService, properties, eventRepository);
        UserAccount user = user("demo", "Demo");

        when(redisCacheService.increment("upload:nonfood:count:demo", Duration.ofMinutes(5))).thenReturn(10L);
        when(eventRepository.findFirstByUsernameAndCreatedAtAfterOrderByCreatedAtAsc(eq("demo"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        NonFoodUploadGuardService.NonFoodUploadDecision decision = service.register(user, "not food");

        assertThat(decision.thresholdReached()).isTrue();
        assertThat(decision.count()).isEqualTo(10);
        verify(redisCacheService).setJson(eq("upload:nonfood:alert:demo"), any(NonFoodUploadAlert.class), eq(Duration.ofMinutes(5)));
        verify(redisCacheService).set("upload:nonfood:block:demo", "blocked", Duration.ofMinutes(60));
        verify(eventRepository).save(any());
    }

    @Test
    void registerDoesNotRaiseAlertBeforeThreshold() {
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        AppProperties properties = new AppProperties();
        properties.getUploadGuard().setNonFoodWindowMinutes(5);
        properties.getUploadGuard().setNonFoodLimit(10);
        NonFoodUploadEventRepository eventRepository = mock(NonFoodUploadEventRepository.class);
        NonFoodUploadGuardService service = new NonFoodUploadGuardService(redisCacheService, properties, eventRepository);

        when(redisCacheService.increment("upload:nonfood:count:demo", Duration.ofMinutes(5))).thenReturn(9L);
        when(eventRepository.findFirstByUsernameAndCreatedAtAfterOrderByCreatedAtAsc(eq("demo"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        NonFoodUploadGuardService.NonFoodUploadDecision decision = service.register(user("demo", "Demo"), "not food");

        assertThat(decision.thresholdReached()).isFalse();
    }

    @Test
    void isBlockedReadsRedisBlockKey() {
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        NonFoodUploadEventRepository eventRepository = mock(NonFoodUploadEventRepository.class);
        NonFoodUploadGuardService service = new NonFoodUploadGuardService(redisCacheService, new AppProperties(), eventRepository);

        when(redisCacheService.get("upload:nonfood:block:demo")).thenReturn(java.util.Optional.of("blocked"));

        assertThat(service.isBlocked("demo")).isTrue();
    }

    @Test
    void remainingBlockMinutesRoundsUpRedisTtl() {
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        NonFoodUploadEventRepository eventRepository = mock(NonFoodUploadEventRepository.class);
        NonFoodUploadGuardService service = new NonFoodUploadGuardService(redisCacheService, new AppProperties(), eventRepository);

        when(redisCacheService.ttl("upload:nonfood:block:demo")).thenReturn(Optional.of(Duration.ofSeconds(2500)));

        assertThat(service.remainingBlockMinutes("demo")).isEqualTo(42);
    }

    private UserAccount user(String username, String displayName) {
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setDisplayName(displayName);
        return user;
    }
}
