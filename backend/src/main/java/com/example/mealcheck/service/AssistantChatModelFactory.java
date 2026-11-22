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
        var connection=properties.getAi().connection();
        return OpenAiChatModel.builder()
                .httpClientBuilder(transport.langchainBuilder(name))
                .maxRetries(0)
                .apiKey(connection.apiKey())
                .baseUrl(connection.baseUrl())
                .modelName(connection.model())
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }
    public String stream(String prompt,AssistantStreamDelivery delivery) {
        try {
            var connection=properties.getAi().connection();
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var body=mapper.createObjectNode();body.put("model",connection.model());body.put("stream",true);
            body.put("temperature",0.35);body.putObject("stream_options").put("include_usage",true);
            body.putArray("messages").addObject().put("role","user").put("content",prompt);
            var request=java.net.http.HttpRequest.newBuilder(AiEndpointResolver.chatCompletions(connection.baseUrl()))
                .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+connection.apiKey())
                .header("Content-Type","application/json").header("Accept","text/event-stream")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
            return transport.stream("diet-assistant",request,body.toString(),delivery);
        } catch(RuntimeException failure) { throw failure; }
    }
}
