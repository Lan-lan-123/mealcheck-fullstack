package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
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

    private final ImageStorageService imageStorageService;
    private final AppProperties properties;
    private final BackgroundTaskDispatcher background;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ImageOrphanCleanupService(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                     ImageStorageService imageStorageService,
                                     AppProperties properties, BackgroundTaskDispatcher background) {
        this.jdbc = jdbc;
        this.imageStorageService = imageStorageService;
        this.properties = properties;
        this.background = background;
    }

    @Scheduled(cron = "${mealcheck.upload.orphan-cleanup-cron:0 30 3 * * *}")
    public void tick() {
        background.submit("image-orphan-cleanup", this::cleanup);
    }

    public void cleanup() {
        if (!properties.getUpload().isOrphanCleanupEnabled()) {
            return;
        }

        long graceHours = Math.max(1L, properties.getUpload().getOrphanGraceHours());
        int deleted = imageStorageService.deleteOrphans(
                this::referenced,
                Instant.now().minus(graceHours, ChronoUnit.HOURS)
        );
        if (deleted > 0) {
            log.info("Removed {} orphaned uploaded images", deleted);
        }
    }
    private java.util.Set<String> referenced(java.util.List<String> batch) {
        java.util.List<String> candidates=new java.util.ArrayList<>(batch);
        for(String reference:batch)if(!reference.startsWith("oss://")) {
            String absolute=imageStorageService.resolve(reference).toString();
            candidates.add(absolute);candidates.add(absolute.replace('\\','/'));
        }
        String placeholders=String.join(",",java.util.Collections.nCopies(candidates.size(),"?"));
        String sql="SELECT stored_image_path AS ref FROM meal_records WHERE stored_image_path IN ("+placeholders+") UNION SELECT input_image_ref AS ref FROM meal_analysis_requests WHERE input_image_ref IN ("+placeholders+")";
        java.util.List<Object> args=new java.util.ArrayList<>(candidates);args.addAll(candidates);
        return new java.util.HashSet<>(jdbc.queryForList(sql,String.class,args.toArray()));
    }
}
