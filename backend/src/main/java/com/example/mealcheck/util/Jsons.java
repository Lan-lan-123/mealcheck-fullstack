package com.example.mealcheck.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class Jsons {
    private Jsons() {}

    public static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    public static <T> T fromJson(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON parse failed", e);
        }
    }

    public static <T> T fromJson(ObjectMapper mapper, String json, TypeReference<T> typeReference) {
        try {
            return mapper.readValue(json, typeReference);
        } catch (Exception e) {
            throw new IllegalStateException("JSON parse failed", e);
        }
    }

    public static String extractJsonObject(String text) {
        if (text == null) return "{}";
        String cleaned = text.replace("```json", "```").trim();
        int fence = cleaned.indexOf("```");
        if (fence >= 0) {
            int endFence = cleaned.indexOf("```", fence + 3);
            if (endFence > fence) {
                cleaned = cleaned.substring(fence + 3, endFence).trim();
            }
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    public static List<?> readListOrEmpty(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return List.of();
        return fromJson(mapper, json, new TypeReference<List<?>>() {});
    }

    public static Map<String, Integer> readStringIntegerMap(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return Map.of();
        return fromJson(mapper, json, new TypeReference<Map<String, Integer>>() {});
    }
}
