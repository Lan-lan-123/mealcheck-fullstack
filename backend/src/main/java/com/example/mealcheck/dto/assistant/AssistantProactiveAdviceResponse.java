package com.example.mealcheck.dto.assistant;

import java.util.List;

public class AssistantProactiveAdviceResponse {
    private List<String> items;

    public AssistantProactiveAdviceResponse(List<String> items) {
        this.items = items;
    }

    public List<String> getItems() { return items; }
}
