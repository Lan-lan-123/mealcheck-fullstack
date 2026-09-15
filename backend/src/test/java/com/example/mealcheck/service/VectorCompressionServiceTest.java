package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VectorCompressionServiceTest {
    @Test
    void usesHalfPrecisionStorageByDefault() {
        VectorCompressionService service = new VectorCompressionService(new AppProperties());

        assertThat(service.columnType(512)).isEqualTo("halfvec(512)");
        assertThat(service.cosineOperatorClass()).isEqualTo("halfvec_cosine_ops");
        assertThat(service.estimatedBytesPerVector(512)).isEqualTo(1032);
    }

    @Test
    void canFallBackToSinglePrecisionVectorStorage() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setVectorStorage("vector");
        VectorCompressionService service = new VectorCompressionService(properties);

        assertThat(service.columnType(512)).isEqualTo("vector(512)");
        assertThat(service.estimatedBytesPerVector(512)).isEqualTo(2056);
    }
}
