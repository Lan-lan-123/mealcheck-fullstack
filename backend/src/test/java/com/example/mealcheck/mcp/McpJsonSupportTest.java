package com.example.mealcheck.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonSupportTest {

    @Test
    void serializesAndDeserializesMcpPayloadsWithJackson2() throws Exception {
        Jackson2McpJsonMapper mapper = new Jackson2McpJsonMapper(new ObjectMapper());

        String json = mapper.writeValueAsString(Map.of("query", "膳食纤维", "limit", 5));
        Map<?, ?> value = mapper.readValue(json, Map.class);

        assertThat(value.get("query")).isEqualTo("膳食纤维");
        assertThat(value.get("limit")).isEqualTo(5);
    }

    @Test
    void validatesRequiredFieldsAndIntegerTypes() {
        SimpleMcpJsonSchemaValidator validator = new SimpleMcpJsonSchemaValidator();
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("query"),
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "limit", Map.of("type", "integer")
                )
        );

        assertThat(validator.validate(schema, Map.of("query", "减脂", "limit", 5)).valid()).isTrue();
        assertThat(validator.validate(schema, Map.of("limit", 5)).valid()).isFalse();
        assertThat(validator.validate(schema, Map.of("query", "减脂", "limit", "five")).valid()).isFalse();
    }
}
