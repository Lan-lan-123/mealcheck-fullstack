package com.example.mealcheck.service;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.AssistantConversationMessageRepository;
import com.example.mealcheck.repository.AssistantConversationRepository;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.NonFoodUploadEventRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceDeletionTest {

    @Test
    void deleteUserReliesOnDatabaseCascadeAndSchedulesImagesAfterCommit() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        MealRecordRepository meals = mock(MealRecordRepository.class);
        ImageStorageService images = mock(ImageStorageService.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        UserAccount target = user(9L, "target", "USER");
        UserPrincipal admin = new UserPrincipal(user(1L, "admin", "ADMIN"));
        MealRecord meal = new MealRecord();
        meal.setStoredImagePath("user-9/meal.jpg");
        when(users.findById(9L)).thenReturn(Optional.of(target));
        when(meals.findByUser(target)).thenReturn(List.of(meal));

        AdminService service = new AdminService(
                users,
                meals,
                mock(NonFoodUploadEventRepository.class),
                mock(AssistantConversationRepository.class),
                mock(AssistantConversationMessageRepository.class),
                mock(KnowledgeIndexService.class),
                mock(PgVectorKnowledgeService.class),
                images,
                new ObjectMapper(),
                mock(AiStatusService.class),
                redis,
                audit,
                mock(NonFoodUploadGuardService.class),
                mock(RagEvaluationService.class),
                mock(RagBenchmarkService.class),
                mock(AssistantProactiveAdviceService.class),
                mock(AdminAnalyticsQueryService.class),
                mock(MealRecordPersistenceService.class)
        );

        service.deleteUser(9L, admin);

        verify(users).delete(target);
        verify(users).flush();
        verify(images).deleteAfterCommit("user-9/meal.jpg");
        verify(redis).delete("admin:stats");
        verify(audit).log(admin, "DELETE_USER", "user", 9L, "delete user target");
    }

    private UserAccount user(long id, String username, String role) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setDisplayName(username);
        user.setRole(role);
        return user;
    }
}
