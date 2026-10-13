package com.example.mealcheck.service;

import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyReportHistoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public WeeklyReportHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    private ReportHistory metadata(java.sql.ResultSet rs) throws java.sql.SQLException {
        var generated = rs.getTimestamp("generated_at");
        return new ReportHistory(rs.getLong("id"),rs.getObject("period_start",java.time.LocalDate.class),rs.getObject("period_end",java.time.LocalDate.class),rs.getInt("days"),generated == null ? null : generated.toLocalDateTime(),rs.getBoolean("stale"));
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<ReportHistory> page(long userId,int page,int size) {
        JournalValidation.page(page,size);
        long total=jdbc.queryForObject("SELECT count(*) FROM weekly_report_snapshots WHERE user_id=?",Long.class,userId);
        var content=jdbc.query("SELECT id,period_start,period_end,days,generated_at,stale FROM weekly_report_snapshots WHERE user_id=? ORDER BY generated_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->metadata(rs),userId,size,(long)page*size);
        return Page.of(content,total,page,size);
    }
    @Transactional(readOnly=true)
    public ArchivedReport owned(long userId,long id) {
        return jdbc.query("SELECT * FROM weekly_report_snapshots WHERE user_id=? AND id=?",(rs,n)->new ArchivedReport(metadata(rs),Jsons.fromJson(mapper,rs.getString("report_json"),WeeklyReportResponse.class)),userId,id)
            .stream().findFirst().orElseThrow(()->JournalValidation.missing("历史周报"));
    }
}
