package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiStatusServiceTest {

    @Test
    void recordsSuccessAndFailureTotals() {
        AiStatusService service = new AiStatusService();

        service.recordSuccess("meal-advice");
        service.recordFailure("vision-recognition", "timeout");

        assertThat(service.successCount()).isEqualTo(1);
        assertThat(service.failureCount()).isEqualTo(1);
        assertThat(service.recentCalls()).hasSize(2);
        assertThat(service.metrics()).hasSize(2);
    }
}
