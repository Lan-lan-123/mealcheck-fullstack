package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AssistantChatModelFactory {
    private final AppProperties properties;
    private final AiHttpTransport transport;

    public AssistantChatModelFactory(AppProperties properties,AiHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    public ChatModel create(Duration timeout) {
        return create(timeout,0.35,"diet-assistant");
    }
    public ChatModel create(Duration timeout,double temperature,String name) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(transport.langchainBuilder(name))
                .maxRetries(0)
                .apiKey(properties.getAi().getApiKey())
                .baseUrl(properties.getAi().getBaseUrl())
                .modelName(properties.getAi().getModel())
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }
}
