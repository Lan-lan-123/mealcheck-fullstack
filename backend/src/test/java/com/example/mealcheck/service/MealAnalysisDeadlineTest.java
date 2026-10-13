package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MealAnalysisDeadlineTest {
    @Test void decodingUsesTheSameDeadlineAndCannotStartModelWorkAfterExpiry() {
        var properties = new AppProperties(); properties.getAiBudget().setOperationTimeoutSeconds(1);
        var validation = mock(ImageUploadValidationService.class);
        var vision = mock(VisionFoodRecognitionService.class);
        var persistence = mock(MealRecordPersistenceService.class);
        var user = new UserAccount(); user.setId(1L); user.setUsername("alice");
        when(validation.validateAndNormalize(any())).thenAnswer(call -> {
            assertThat(AiCallContext.capture().userId()).isEqualTo(1L);
            Thread.sleep(1100);
            return new ValidatedImage(new byte[]{1}, "image/jpeg", ".jpg", 1, 1, "meal.jpg");
        });
        var analysis = new MealAnalysisService(mock(UserAccountRepository.class), mock(MealRecordRepository.class),
                vision, mock(MealScoringService.class), mock(KnowledgeIndexService.class), mock(AdviceGenerationService.class),
                mock(ImageStorageService.class), validation, mock(NonFoodUploadGuardService.class), mock(UserGoalService.class),
                persistence, new ObjectMapper(), properties, null);
        assertThatThrownBy(() -> analysis.analyze(new UserPrincipal(user),
                new MockMultipartFile("image", "meal.jpg", "image/jpeg", new byte[]{1}), "balanced"))
                .isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        verifyNoInteractions(vision, persistence);
        assertThat(AiCallContext.capture()).isNull();
    }
}
