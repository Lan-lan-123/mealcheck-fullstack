package com.example.mealcheck.config;

import com.example.mealcheck.util.AppTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.time.*;
import java.util.TimeZone;
import static org.assertj.core.api.Assertions.assertThat;

class ApiTimeConfigurationTest {
    record Payload(LocalDateTime at, LocalDate day) {}
    ObjectMapper mapper() {
        var builder=new Jackson2ObjectMapperBuilder();
        new ApiTimeConfiguration().explicitTimestampOffsets().customize(builder);
        return builder.build();
    }
    @Test void timestampOffsetsRoundTripAndLegacySnapshotsRemainReadable() throws Exception {
        var mapper=mapper();
        var value=new Payload(LocalDateTime.of(2026,10,8,16,5),LocalDate.of(2026,10,9));
        String json=mapper.writeValueAsString(value);
        assertThat(json).contains("2026-10-08T16:05:00Z");
        assertThat(mapper.readValue(json,Payload.class)).isEqualTo(value);
        assertThat(mapper.readValue("{\"at\":\"2026-10-09T00:05:00+08:00\",\"day\":\"2026-10-09\"}",Payload.class)).isEqualTo(value);
        assertThat(mapper.readValue("{\"at\":\"2026-10-08T16:05:00\",\"day\":\"2026-10-09\"}",Payload.class)).isEqualTo(value);
    }
    @Test void businessDatesAndRangeBoundariesDoNotDependOnHostTimezone() {
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertThat(AppTime.businessDate(LocalDateTime.of(2026,10,8,15,59))).isEqualTo(LocalDate.of(2026,10,8));
            assertThat(AppTime.businessDate(LocalDateTime.of(2026,10,8,16,0))).isEqualTo(LocalDate.of(2026,10,9));
            assertThat(AppTime.startOfDay(LocalDate.of(2026,10,9))).isEqualTo(LocalDateTime.of(2026,10,8,16,0));
            assertThat(Duration.between(AppTime.now(),LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC)).abs()).isLessThan(Duration.ofSeconds(2));
        } finally {TimeZone.setDefault(original);}
    }
    @Test void localAndOffsetFilterInputsReferToTheSameInstant() {
        var expected=LocalDateTime.of(2026,10,8,16,5);
        assertThat(AppTime.parseFilterTime("2026-10-09T00:05")).isEqualTo(expected);
        assertThat(AppTime.parseFilterTime("2026-10-09T00:05:00+08:00")).isEqualTo(expected);
        assertThat(AppTime.parseFilterTime("2026-10-08T16:05:00Z")).isEqualTo(expected);
        assertThat(AppTime.parseFilterTime("")).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AppTime.parseFilterTime("bad")).isInstanceOf(IllegalArgumentException.class);
    }
}
