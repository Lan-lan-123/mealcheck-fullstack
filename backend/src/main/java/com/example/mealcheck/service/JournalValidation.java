package com.example.mealcheck.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class JournalValidation {
    private JournalValidation() { }
    static void range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365
                || from.isBefore(LocalDate.of(2000, 1, 1)))
            throw new IllegalArgumentException("日期范围须在 2000 年之后，且最多 366 天。");
    }
    static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("页码须非负，每页数量须为 1 到 100。");
    }
    static ResponseStatusException missing(String type) { return new ResponseStatusException(HttpStatus.NOT_FOUND, type + "不存在"); }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "内容已被修改，请刷新后再试。"); }
}
