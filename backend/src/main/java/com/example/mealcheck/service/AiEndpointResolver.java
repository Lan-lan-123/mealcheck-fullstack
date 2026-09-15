package com.example.mealcheck.service;

import java.net.URI;

final class AiEndpointResolver {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String EMBEDDINGS_PATH = "/embeddings";

    private AiEndpointResolver() {
    }

    static URI chatCompletions(String baseUrl) {
        return resolve(baseUrl, CHAT_COMPLETIONS_PATH);
    }

    static URI embeddings(String baseUrl) {
        return resolve(baseUrl, EMBEDDINGS_PATH);
    }

    private static URI resolve(String baseUrl, String endpointPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("AI base URL is not configured.");
        }

        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.endsWith(CHAT_COMPLETIONS_PATH)) {
            normalized = normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_PATH.length());
        } else if (normalized.endsWith(EMBEDDINGS_PATH)) {
            normalized = normalized.substring(0, normalized.length() - EMBEDDINGS_PATH.length());
        }
        return URI.create(normalized + endpointPath);
    }
}
