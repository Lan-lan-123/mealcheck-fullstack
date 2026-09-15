package com.example.mealcheck.service;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MealRecordPersistenceServiceTest {

    @Test
    void removesStoredFileImmediatelyWhenPersistenceFailsOutsideTransactionSynchronization() {
        MealRecordRepository records = mock(MealRecordRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserDietProfileService profiles = mock(UserDietProfileService.class);
        ImageStorageService images = mock(ImageStorageService.class);
        MealRecordPersistenceService service = new MealRecordPersistenceService(records, users, profiles, images);
        UserAccount user = user(7L);
        MealRecord record = new MealRecord();
        ValidatedImage image = image();
        when(images.store(7L, image)).thenReturn("user-7/image.jpg");
        when(images.deleteOnRollback("user-7/image.jpg")).thenReturn(false);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(records.saveAndFlush(record)).thenThrow(new IllegalStateException("database failed"));

        assertThatThrownBy(() -> service.saveWithImage(user, record, image))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failed");

        verify(images).deleteSafely("user-7/image.jpg");
    }

    @Test
    void reliesOnRegisteredRollbackCallbackWhenTransactionSynchronizationIsActive() {
        MealRecordRepository records = mock(MealRecordRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserDietProfileService profiles = mock(UserDietProfileService.class);
        ImageStorageService images = mock(ImageStorageService.class);
        MealRecordPersistenceService service = new MealRecordPersistenceService(records, users, profiles, images);
        UserAccount user = user(8L);
        MealRecord record = new MealRecord();
        ValidatedImage image = image();
        when(images.store(8L, image)).thenReturn("user-8/image.jpg");
        when(images.deleteOnRollback("user-8/image.jpg")).thenReturn(true);
        when(users.findByIdForUpdate(8L)).thenThrow(new IllegalStateException("database failed"));

        assertThatThrownBy(() -> service.saveWithImage(user, record, image))
                .isInstanceOf(IllegalStateException.class);

        verify(images, never()).deleteSafely("user-8/image.jpg");
        assertThat(record.getStoredImagePath()).isEqualTo("user-8/image.jpg");
    }

    private UserAccount user(long id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        return user;
    }

    private ValidatedImage image() {
        return new ValidatedImage(new byte[]{1}, "image/jpeg", ".jpg", 1, 1, "meal.jpg");
    }
}
