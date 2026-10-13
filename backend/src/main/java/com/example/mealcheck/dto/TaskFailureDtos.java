package com.example.mealcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class TaskFailureDtos {
    private TaskFailureDtos() { }
    public record Item(long id, String taskType, Long userId, String taskKey, String eventId,
            String phase, String errorCode, String errorSummary, String status, int attempts,
            boolean deadLettered, long version, LocalDateTime failedAt, LocalDateTime updatedAt,
            String taskStatus, int manualRetryCount, boolean retryable, String retryBlockedReason,
            String handlingNote) { }
    public record Page(List<Item> content, long totalElements, int totalPages, int number, int size) { }
    public record Operation(String operationId, String action, Long adminId, String reason, LocalDateTime createdAt) { }
    public record Detail(Item failure, List<Item> history, List<Operation> operations) { }
    public record Action(@NotNull UUID operationId, @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 500) String reason) { }
    public record Summary(long open, long retrying, long oldestOpenSeconds, long actionable, long oldestActionableSeconds) { }
}
