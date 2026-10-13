package com.example.mealcheck.repository;

import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.dto.JournalDtos.BehaviorGoal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class BehaviorGoalRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Goal> mapper = (rs, n) -> new Goal(rs.getLong("id"), rs.getString("kind"), rs.getInt("target"),
        rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(), rs.getBoolean("active"), rs.getLong("version"));
    public BehaviorGoalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Goal> owned(long userId, long id) {
        return jdbc.query("SELECT * FROM behavior_goals WHERE user_id=? AND id=?", mapper, userId, id).stream().findFirst();
    }
    public Page<Goal> page(long userId, int page, int size) {
        long total = jdbc.queryForObject("SELECT count(*) FROM behavior_goals WHERE user_id=?", Long.class, userId);
        var content = jdbc.query("SELECT * FROM behavior_goals WHERE user_id=? ORDER BY active DESC,start_date DESC,id DESC LIMIT ? OFFSET ?",
            mapper, userId, size, (long) page * size);
        return Page.of(content, total, page, size);
    }
    public List<Goal> overlapping(long userId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM behavior_goals WHERE user_id=? AND active AND start_date<=? AND end_date>=? ORDER BY start_date,id", mapper, userId, to, from);
    }
    public boolean overlaps(long userId, Long id, BehaviorGoal body) {
        return jdbc.queryForObject("SELECT count(*) FROM behavior_goals WHERE user_id=? AND kind=? AND active AND start_date<=? AND end_date>=? AND id<>?",
            Long.class, userId, body.kind(), body.endDate(), body.startDate(), id == null ? -1 : id) > 0;
    }
    public long insert(long userId, BehaviorGoal body) {
        return jdbc.queryForObject("INSERT INTO behavior_goals(user_id,kind,target,start_date,end_date,active) VALUES(?,?,?,?,?,?) RETURNING id",
            Long.class, userId, body.kind(), body.target(), body.startDate(), body.endDate(), body.active());
    }
    public void update(long userId, long id, BehaviorGoal body) {
        jdbc.update("UPDATE behavior_goals SET kind=?,target=?,start_date=?,end_date=?,active=?,version=version+1 WHERE id=? AND user_id=?",
            body.kind(), body.target(), body.startDate(), body.endDate(), body.active(), id, userId);
    }
}
