package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminMealQueryServiceTest {
    @Test void cursorTimestampAcceptsReturnedOffsetsAndEquivalentInstants() {
        var time = LocalDateTime.of(2026, 10, 9, 12, 0);
        var zoned = time.atZone(com.example.mealcheck.util.AppTime.storageZone());
        assertThat(AdminMealQueryService.parseCursorTime(zoned.toOffsetDateTime().toString())).isEqualTo(time);
        assertThat(AdminMealQueryService.parseCursorTime(zoned.withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                .toOffsetDateTime().toString())).isEqualTo(time);
        assertThat(AdminMealQueryService.parseCursorTime(time.toString())).isEqualTo(time);
        assertThatThrownBy(() -> AdminMealQueryService.parseCursorTime("bad-time"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void cursorFetchesOnlyOneExtraRowWithoutCountingAndExposesLastVisibleIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var time = LocalDateTime.of(2026, 10, 9, 12, 0);
        List<AdminMealRecordResponse> rows = List.of(row(5, time), row(4, time), row(3, time));
        doAnswer(call -> {
            String sql = call.getArgument(0);
            Object[] args = call.getRawArguments().length == 3 ? (Object[]) call.getRawArguments()[2] : null;
            assertThat(sql).contains("WITH meal_page AS MATERIALIZED", "(m.created_at,m.id) < (?,?)", "LEFT JOIN users");
            assertThat(sql).doesNotContain("count(*)", "OFFSET", "advice", "original_foods_json", "m.*");
            assertThat(args).containsExactly(time, 6L, 3);
            return rows;
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        var page = new AdminMealQueryService(jdbc, new ObjectMapper())
                .cursor(2, null, null, null, null, null, time, 6L);
        assertThat(page.content()).extracting(AdminMealRecordResponse::getId).containsExactly(5L, 4L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextBeforeId()).isEqualTo(4L);
        assertThat(page.nextBeforeTime()).isEqualTo(time);
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test void numberedPagesUseLongOffsetsAndApplyExclusiveDateBoundaryToCountAndRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(12L);
        doAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("m.created_at<?", "ORDER BY m.created_at DESC,m.id DESC", "LIMIT ? OFFSET ?");
            Object[] args = (Object[]) call.getRawArguments()[2];
            assertThat(args[args.length - 1]).isEqualTo(214748364700L);
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        var page = new AdminMealQueryService(jdbc, new ObjectMapper()).page(Integer.MAX_VALUE, 100,
                "ALICE", LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9), 60, 90);
        assertThat(page.getTotalElements()).isEqualTo(12);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    private AdminMealRecordResponse row(long id, LocalDateTime time) {
        return new AdminMealRecordResponse(id, "alice", 80, "summary", List.of(), time);
    }
}
