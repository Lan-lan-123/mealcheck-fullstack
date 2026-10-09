package com.example.mealcheck.service;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccountDeletionServiceTest {
    @Test
    void deletesOwnedDataAndImagesAfterPasswordConfirmation() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        MealRecordRepository meals = mock(MealRecordRepository.class);
        ImageStorageService images = mock(ImageStorageService.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount user = user("demo", "USER");
        user.setId(7L);
        MealRecord meal = new MealRecord();
        meal.setStoredImagePath("user-7/meal.jpg");
        when(passwords.matches("correct-password", "encoded")).thenReturn(true);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(meals.findByUser(user)).thenReturn(List.of(meal));
        UserAccountDeletionService service = new UserAccountDeletionService(users, meals, images, redis, passwords);

        service.deleteCurrentAccount(new UserPrincipal(user), "correct-password");

        verify(users).delete(user);
        verify(users).flush();
        verify(images).deleteAfterCommit("user-7/meal.jpg");
        verify(redis).deleteByPrefix("assistant:history:demo:");
    }

    @Test
    void rejectsWrongPasswordWithoutDeletingAnything() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount user = user("demo", "USER");
        user.setId(7L);
        UserAccountDeletionService service = new UserAccountDeletionService(
                users, mock(MealRecordRepository.class), mock(ImageStorageService.class),
                mock(RedisCacheService.class), passwords);

        assertThatThrownBy(() -> service.deleteCurrentAccount(new UserPrincipal(user), "wrong"))
                .isInstanceOf(BadCredentialsException.class);
        verify(users, never()).delete(user);
    }

    private UserAccount user(String username, String role) {
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash("encoded");
        user.setDisplayName(username);
        user.setRole(role);
        return user;
    }
}
