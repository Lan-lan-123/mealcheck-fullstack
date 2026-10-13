package com.example.mealcheck.util;

import java.time.*;

/** Explicit storage and business zones, independent of the host's default timezone. */
public final class AppTime {
    private static final ZoneId STORAGE = ZoneId.of(System.getProperty("mealcheck.time.storage-zone",
            System.getenv().getOrDefault("MEALCHECK_TIMESTAMP_STORAGE_ZONE", "UTC")));
    private static final ZoneId BUSINESS = ZoneId.of(System.getProperty("mealcheck.time.business-zone",
            System.getenv().getOrDefault("MEALCHECK_BUSINESS_ZONE", "Asia/Shanghai")));
    private AppTime() {}
    public static ZoneId storageZone() { return STORAGE; }
    public static ZoneId businessZone() { return BUSINESS; }
    public static LocalDateTime now() { return LocalDateTime.now(STORAGE); }
    public static LocalDate today() { return LocalDate.now(BUSINESS); }
    public static LocalDate businessDate(LocalDateTime value) {
        return value.atZone(STORAGE).withZoneSameInstant(BUSINESS).toLocalDate();
    }
    public static LocalDateTime startOfDay(LocalDate day) {
        return LocalDateTime.ofInstant(day.atStartOfDay(BUSINESS).toInstant(), STORAGE);
    }
    public static LocalDateTime parseFilterTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(STORAGE).toLocalDateTime();
            } catch (java.time.format.DateTimeParseException noOffset) {
                return LocalDateTime.parse(value).atZone(BUSINESS).withZoneSameInstant(STORAGE).toLocalDateTime();
            }
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("筛选时间格式不正确", invalid);
        }
    }
}
