package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDate;

/** Cursor and job inserts commit together; concurrent replicas skip the claimed batch. */
@Service
public class WeeklyReportDispatchService {
    private final JdbcTemplate jdbc;
    private final WeeklyReportJobService jobs;
    @Value("${mealcheck.weekly-report.batch-size:100}") private int batchSize=100;
    @Value("${mealcheck.weekly-report.active-days:30}") private int activeDays=30;
    public WeeklyReportDispatchService(JdbcTemplate jdbc, WeeklyReportJobService jobs) { this.jdbc=jdbc;this.jobs=jobs; }
    @Transactional(timeout=10)
    public void start(LocalDate week) {
        jdbc.update("INSERT INTO weekly_report_dispatch(report_week,upper_id) SELECT ?,coalesce(max(id),0) FROM users ON CONFLICT DO NOTHING",week);
    }
    @Transactional(timeout=10)
    public int dispatchBatch() {
        var rows=jdbc.queryForList("SELECT * FROM weekly_report_dispatch WHERE status='ACTIVE' ORDER BY report_week LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) return 0;
        var row=rows.get(0);
        LocalDate week=((java.sql.Date)row.get("report_week")).toLocalDate();
        long cursor=((Number)row.get("cursor_id")).longValue(),upper=((Number)row.get("upper_id")).longValue();
        var ids=jdbc.queryForList("""
                SELECT u.id FROM users u WHERE u.id>? AND u.id<=?
                  AND EXISTS(SELECT 1 FROM meal_records m WHERE m.user_id=u.id AND m.created_at>=? AND m.created_at<?)
                ORDER BY u.id LIMIT ?
                """,Long.class,cursor,upper,AppTime.startOfDay(week.minusDays(Math.max(1,activeDays))),
                AppTime.startOfDay(week.plusDays(1)),Math.max(10,Math.min(500,batchSize)));
        int inserted=ids.isEmpty()?0:jobs.enqueue(ids,7,week);
        boolean done=ids.size()<Math.max(10,Math.min(500,batchSize));
        jdbc.update("UPDATE weekly_report_dispatch SET cursor_id=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE report_week=?",
                ids.isEmpty()?upper:ids.get(ids.size()-1),done?"DONE":"ACTIVE",week);
        return inserted;
    }
}
