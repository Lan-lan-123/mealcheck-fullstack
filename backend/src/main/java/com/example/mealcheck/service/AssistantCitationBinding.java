package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Source identity validation only; a valid ID does not prove that a claim is supported. */
final class AssistantCitationBinding {
    static final int MAX_REFERENCES = 3;
    static final String STREAM_START = "<mealcheck-citations>";
    static final String STREAM_END = "</mealcheck-citations>";

    private AssistantCitationBinding() { }

    static int streamMetadataStart(String text) {
        int marker = text.indexOf(STREAM_START);
        if (marker >= 0) return marker;
        // An interrupted/malformed footer must not expose a partial internal tag either.
        for (int length = Math.min(text.length(), STREAM_START.length() - 1); length >= 2; length--)
            if (text.endsWith(STREAM_START.substring(0, length))) return text.length() - length;
        return -1;
    }

    static List<AssistantReference> select(JsonNode response, List<AssistantReference> candidates) {
        // Missing or malformed metadata must never turn retrieved candidates into used sources.
        if (response == null || !response.isObject()
                || !response.path("refused").isBoolean() || response.path("refused").booleanValue()
                || !response.path("referenceIds").isArray() || candidates == null) return List.of();

        var available = new LinkedHashMap<Long, AssistantReference>();
        for (var candidate : candidates) {
            if (candidate != null && candidate.getId() != null && candidate.getId() > 0)
                available.putIfAbsent(candidate.getId(), candidate);
        }
        var selected = new ArrayList<AssistantReference>();
        var seen = new LinkedHashSet<Long>();
        for (var value : response.path("referenceIds")) {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) continue;
            long id = value.longValue();
            if (id > 0 && available.containsKey(id) && seen.add(id)) selected.add(available.get(id));
            if (selected.size() == MAX_REFERENCES) break;
        }
        return List.copyOf(selected);
    }
}
