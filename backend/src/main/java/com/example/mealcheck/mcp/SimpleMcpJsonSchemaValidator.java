package com.example.mealcheck.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

import java.util.List;
import java.util.Map;

public class SimpleMcpJsonSchemaValidator implements JsonSchemaValidator {
    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object value) {
        if (schema == null || schema.isEmpty()) {
            return ValidationResponse.asValid("{}");
        }
        if ("object".equals(schema.get("type")) && !(value instanceof Map<?, ?>)) {
            return ValidationResponse.asInvalid("Expected a JSON object");
        }
        if (!(value instanceof Map<?, ?> values)) {
            return ValidationResponse.asValid("{}");
        }

        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> required) {
            for (Object field : required) {
                if (!values.containsKey(String.valueOf(field)) || values.get(String.valueOf(field)) == null) {
                    return ValidationResponse.asInvalid("Missing required argument: " + field);
                }
            }
        }

        Object propertiesValue = schema.get("properties");
        if (propertiesValue instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                Object fieldValue = values.get(String.valueOf(entry.getKey()));
                if (fieldValue == null || !(entry.getValue() instanceof Map<?, ?> definition)) {
                    continue;
                }
                String expectedType = String.valueOf(definition.get("type"));
                if ("string".equals(expectedType) && !(fieldValue instanceof String)) {
                    return ValidationResponse.asInvalid(entry.getKey() + " must be a string");
                }
                if ("integer".equals(expectedType) && !(fieldValue instanceof Number)) {
                    return ValidationResponse.asInvalid(entry.getKey() + " must be an integer");
                }
            }
        }
        return ValidationResponse.asValid("{}");
    }
}
