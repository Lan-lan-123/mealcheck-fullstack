package com.example.mealcheck.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitHttpHeadersTest {

    @Test
    void roundsRetryAfterUpToWholeSeconds() {
        HttpHeaders headers = RateLimitHttpHeaders.from(5, 0, Duration.ofMillis(2500));

        assertThat(headers.getFirst("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(headers.getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
    }
}
