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
        // Caller holds the account lock. Preserve history and fence active attempts.
        jdbc.update("UPDATE weekly_report_jobs SET status='STALE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND status <> 'STALE'", userId);
        jdbc.update("DELETE FROM weekly_report_snapshots WHERE user_id=?", userId);
    }
}
