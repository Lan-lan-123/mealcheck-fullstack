package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MealRecordPersistenceService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final MealRecordRepository mealRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserDietProfileService profileService;
    private final ImageStorageService imageStorageService;
    private final MealReportInvalidationService invalidation;

    public MealRecordPersistenceService(MealRecordRepository mealRecordRepository,
                                        UserAccountRepository userAccountRepository,
                                        UserDietProfileService profileService,
                                        ImageStorageService imageStorageService,
                                        MealReportInvalidationService invalidation) {
        this.mealRecordRepository = mealRecordRepository;
        this.userAccountRepository = userAccountRepository;
        this.profileService = profileService;
        this.imageStorageService = imageStorageService;
        this.invalidation = invalidation;
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
            lockedUser.setLastUploadAt(AppTime.now());
            userAccountRepository.save(lockedUser);
            profileService.applyMeal(lockedUser, saved);
            invalidation.invalidate(lockedUser.getId());
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
        entityManager.refresh(record);
        profileService.removeMeal(lockedUser, record);
        mealRecordRepository.delete(record);
        invalidation.invalidate(lockedUser.getId());
    }
}
