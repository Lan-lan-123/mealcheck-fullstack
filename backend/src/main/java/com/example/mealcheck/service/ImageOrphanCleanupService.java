package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.repository.MealRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;

@Service
public class ImageOrphanCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ImageOrphanCleanupService.class);

    private final MealRecordRepository mealRecordRepository;
    private final ImageStorageService imageStorageService;
    private final AppProperties properties;

    public ImageOrphanCleanupService(MealRecordRepository mealRecordRepository,
                                     ImageStorageService imageStorageService,
                                     AppProperties properties) {
        this.mealRecordRepository = mealRecordRepository;
        this.imageStorageService = imageStorageService;
        this.properties = properties;
    }

    @Scheduled(cron = "${mealcheck.upload.orphan-cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        if (!properties.getUpload().isOrphanCleanupEnabled()) {
            return;
        }

        HashSet<String> referenced = new HashSet<>(mealRecordRepository.findAllStoredImagePaths());
        long graceHours = Math.max(1L, properties.getUpload().getOrphanGraceHours());
        int deleted = imageStorageService.deleteOrphans(
                referenced,
                Instant.now().minus(graceHours, ChronoUnit.HOURS)
        );
        if (deleted > 0) {
            log.info("Removed {} orphaned uploaded images", deleted);
        }
    }
}
