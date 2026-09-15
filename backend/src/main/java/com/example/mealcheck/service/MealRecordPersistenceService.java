package com.example.mealcheck.service;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MealRecordPersistenceService {
    private final MealRecordRepository mealRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserDietProfileService profileService;
    private final ImageStorageService imageStorageService;

    public MealRecordPersistenceService(MealRecordRepository mealRecordRepository,
                                        UserAccountRepository userAccountRepository,
                                        UserDietProfileService profileService,
                                        ImageStorageService imageStorageService) {
        this.mealRecordRepository = mealRecordRepository;
        this.userAccountRepository = userAccountRepository;
        this.profileService = profileService;
        this.imageStorageService = imageStorageService;
    }

    @Transactional
    public MealRecord saveWithImage(UserAccount user, MealRecord record, ValidatedImage image) {
        String storedPath = imageStorageService.store(user.getId(), image);
        boolean rollbackCleanupRegistered = imageStorageService.deleteOnRollback(storedPath);
        try {
            record.setStoredImagePath(storedPath);
            UserAccount lockedUser = userAccountRepository.findByIdForUpdate(user.getId()).orElseThrow();
            record.setUser(lockedUser);
            MealRecord saved = mealRecordRepository.saveAndFlush(record);
            lockedUser.setLastUploadAt(LocalDateTime.now());
            userAccountRepository.save(lockedUser);
            profileService.applyMeal(lockedUser, saved);
            return saved;
        } catch (RuntimeException e) {
            if (!rollbackCleanupRegistered) {
                imageStorageService.deleteSafely(storedPath);
            }
            throw e;
        }
    }

    @Transactional
    public void delete(MealRecord record) {
        UserAccount lockedUser = userAccountRepository.findByIdForUpdate(record.getUser().getId()).orElseThrow();
        profileService.removeMeal(lockedUser, record);
        mealRecordRepository.delete(record);
    }
}
