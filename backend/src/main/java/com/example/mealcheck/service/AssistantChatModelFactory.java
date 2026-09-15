package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AssistantChatModelFactory {
    private final AppProperties properties;

    public AssistantChatModelFactory(AppProperties properties) {
        this.properties = properties;
    }

    public ChatModel create(Duration timeout) {
        return OpenAiChatModel.builder()
                .apiKey(properties.getAi().getApiKey())
                .baseUrl(properties.getAi().getBaseUrl())
                .modelName(properties.getAi().getModel())
                .temperature(0.35)
                .timeout(timeout)
                .build();
    }
}
