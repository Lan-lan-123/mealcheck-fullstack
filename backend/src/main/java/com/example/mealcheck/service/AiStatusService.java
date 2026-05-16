package com.example.mealcheck.service;

import com.example.mealcheck.dto.AiCallStatusResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiStatusService {
    private final Map<String, AiCallStatusResponse> statuses = new ConcurrentHashMap<>();

    public void recordSuccess(String operation) {
        statuses.put(operation, new AiCallStatusResponse(operation, true, "最近调用成功", LocalDateTime.now()));
    }

    public void recordFailure(String operation, String message) {
        String safeMessage = message == null || message.isBlank() ? "最近调用失败" : message;
        statuses.put(operation, new AiCallStatusResponse(operation, false, safeMessage, LocalDateTime.now()));
    }

    public List<AiCallStatusResponse> recentCalls() {
        return statuses.values().stream()
                .sorted((left, right) -> right.getAt().compareTo(left.getAt()))
                .toList();
    }
}
