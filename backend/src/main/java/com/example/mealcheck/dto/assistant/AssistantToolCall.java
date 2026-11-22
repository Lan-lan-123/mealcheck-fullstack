package com.example.mealcheck.dto.assistant;

/** User-visible evidence of a real, authenticated tool execution; no model reasoning is exposed. */
public record AssistantToolCall(String name, String arguments, String status, int round,
                                long durationMs, String origin, String resultSummary) { }
