package com.example.mealcheck.mcp;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;

public class Jackson2McpJsonMapper implements McpJsonMapper {
    private final ObjectMapper objectMapper;

    public Jackson2McpJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return objectMapper.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return objectMapper.readValue(content, type);
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return objectMapper.readValue(content, javaType(type));
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return objectMapper.readValue(content, javaType(type));
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        return objectMapper.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        return objectMapper.convertValue(value, javaType(type));
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return objectMapper.writeValueAsBytes(value);
    }

    private JavaType javaType(TypeRef<?> type) {
        return objectMapper.getTypeFactory().constructType(type.getType());
    }
}
