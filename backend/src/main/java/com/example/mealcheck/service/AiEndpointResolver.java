package com.example.mealcheck.service;

import java.net.URI;

final class AiEndpointResolver {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private AiEndpointResolver() {
    }

    static URI chatCompletions(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("AI base URL is not configured.");
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (!normalized.endsWith(CHAT_COMPLETIONS_PATH)) {
            normalized += CHAT_COMPLETIONS_PATH;
        }

        return URI.create(normalized);
    }
}
