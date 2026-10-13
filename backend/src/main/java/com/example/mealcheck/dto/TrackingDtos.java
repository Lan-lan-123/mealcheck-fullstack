package com.example.mealcheck.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class TrackingDtos {
    private TrackingDtos() { }
    public record Goal(long id, String kind, int target, LocalDate startDate, LocalDate endDate,
                       boolean active, long version) implements Serializable { }
    public record Progress(Goal goal, LocalDate periodStart, LocalDate periodEnd, int completed,
                           int remaining, boolean achieved, int eligibleDays, int daysAchieved) implements Serializable { }
    public record PlanView(long id, String title, String actionText, String sourceType, Long sourceRecordId,
                           Long sourceMessageId, LocalDate plannedDate, String status, String note, long version,
                           String completedAt, LocalDate sourcePeriodStart, LocalDate sourcePeriodEnd) implements Serializable { }
    public record Day(LocalDate day, int meals, Map<String, String> slots) implements Serializable { }
    public record Summary(LocalDate periodStart, LocalDate periodEnd, int totalMeals, int recordedDays, int days,
                          double coveragePercent, int confirmedSkippedSlots, int unknownSlots, List<Day> daily,
                          List<Progress> goals, Map<String, Integer> plans, String explanation) implements Serializable { }
    public record Page<T>(List<T> content, long totalElements, int totalPages, int page, int size) {
        public static <T> Page<T> of(List<T> content, long total, int page, int size) {
            return new Page<>(List.copyOf(content), total, (int) Math.ceil((double) total / size), page, size);
        }
    }
    public record AnswerFeedback(String verdict, String category, String note, String requestId) { }
    public record ReportHistory(long id, LocalDate periodStart, LocalDate periodEnd, int days,
                                java.time.LocalDateTime generatedAt, boolean stale) { }
    public record ArchivedReport(ReportHistory snapshot, WeeklyReportResponse report) { }
}
