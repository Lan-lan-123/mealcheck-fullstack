package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MealReportInvalidationService {
    private final JdbcTemplate jdbc;
    public MealReportInvalidationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidate(Long userId) {
        // A change to dietary direction affects all narratives, but keeps their archived versions.
        jdbc.update("UPDATE weekly_report_jobs SET status='STALE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND status <> 'STALE'", userId);
        jdbc.update("UPDATE weekly_report_snapshots SET stale=true,invalidated_at=CURRENT_TIMESTAMP WHERE user_id=? AND stale=false", userId);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void mealDates(Long userId, java.time.LocalDate... dates) {
        for (var day : new java.util.HashSet<>(java.util.Arrays.asList(dates))) {
            jdbc.update("""
                UPDATE weekly_report_jobs SET status='STALE',version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND status<>'STALE'
                  AND CAST(created_at AT TIME ZONE ? AT TIME ZONE ? AS date)>=?
                  AND CAST(created_at AT TIME ZONE ? AT TIME ZONE ? AS date)-(days-1)<=?
                """, userId, com.example.mealcheck.util.AppTime.storageZone().getId(), com.example.mealcheck.util.AppTime.businessZone().getId(), day,
                com.example.mealcheck.util.AppTime.storageZone().getId(), com.example.mealcheck.util.AppTime.businessZone().getId(), day);
            jdbc.update("""
                UPDATE weekly_report_snapshots SET stale=true,invalidated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND stale=false AND (period_start IS NULL OR period_end IS NULL OR (period_start<=? AND period_end>=?))
                """, userId, day, day);
        }
    }
}
