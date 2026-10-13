package com.example.mealcheck.config;

import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;

@Configuration
public class ApiTimeConfiguration {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer explicitTimestampOffsets() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                @Override public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                    gen.writeString(value.atZone(AppTime.storageZone()).toOffsetDateTime()
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
            });
            // Accept existing snapshot JSON as well as the new offset-bearing format.
            builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                @Override public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    if (parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) {
                        String value = parser.getText().trim();
                        try {
                            return OffsetDateTime.parse(value).atZoneSameInstant(AppTime.storageZone()).toLocalDateTime();
                        } catch (java.time.format.DateTimeParseException ignored) {
                            return LocalDateTimeDeserializer.INSTANCE.deserialize(parser, context);
                        }
                    }
                    return LocalDateTimeDeserializer.INSTANCE.deserialize(parser, context);
                }
            });
        };
    }
}
