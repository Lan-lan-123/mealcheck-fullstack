package com.example.mealcheck.service;

import com.example.mealcheck.dto.UserGoalRequest;
import com.example.mealcheck.dto.UserGoalResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserGoal;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.repository.UserGoalRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserGoalServiceTest {

    @Test
    void updateDeactivatesPreviousGoalAndCreatesNewCurrentGoal() {
        UserGoalRepository goalRepository = mock(UserGoalRepository.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        UserGoalService service = new UserGoalService(goalRepository, userRepository);

        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash("hash");

        UserGoal previous = new UserGoal();
        previous.setUser(user);
        previous.setGoalType("balanced");

        UserGoalRequest request = new UserGoalRequest();
        request.setGoalType("fat_loss");
        request.setEndDate(LocalDate.now().plusWeeks(4));
        request.setNote("prepare for summer");

        when(userRepository.findByUsername("demo")).thenReturn(Optional.of(user));
        when(goalRepository.findFirstByUserAndActiveTrueOrderByCreatedAtDesc(user)).thenReturn(Optional.of(previous));
        when(goalRepository.save(any(UserGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserGoalResponse response = service.update(new UserPrincipal(user), request);

        assertThat(previous.isActive()).isFalse();
        assertThat(response.getGoalType()).isEqualTo("fat_loss");
        assertThat(response.getEndDate()).isEqualTo(request.getEndDate());
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void effectiveGoalUsesStoredGoalWhenRequestIsCurrent() {
        UserGoalRepository goalRepository = mock(UserGoalRepository.class);
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        UserGoalService service = new UserGoalService(goalRepository, userRepository);

        UserAccount user = new UserAccount();
        UserGoal goal = new UserGoal();
        goal.setGoalType("muscle_gain");
        when(goalRepository.findFirstByUserAndActiveTrueOrderByCreatedAtDesc(user)).thenReturn(Optional.of(goal));

        assertThat(service.effectiveGoal(user, "current")).isEqualTo("muscle_gain");
    }
}
