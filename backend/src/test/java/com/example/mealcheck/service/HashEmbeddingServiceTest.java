package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashEmbeddingServiceTest {

    @Test
    void embedReturnsConfiguredDimensionAndNormalizedVector() {
        HashEmbeddingService service = new HashEmbeddingService(propertiesWithDimension(16));

        float[] vector = service.embed("rice chicken vegetable");

        assertThat(vector).hasSize(16);
        assertThat(l2Norm(vector)).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void embedReturnsZeroVectorForBlankText() {
        HashEmbeddingService service = new HashEmbeddingService(propertiesWithDimension(8));

        float[] vector = service.embed("   ");

        assertThat(vector).containsOnly(0.0f);
    }

    @Test
    void toPgVectorUsesPgvectorArraySyntax() {
        HashEmbeddingService service = new HashEmbeddingService(propertiesWithDimension(3));

        String pgVector = service.toPgVector(new float[] {1.0f, -0.25f, 0.5f});

        assertThat(pgVector).isEqualTo("[1.000000,-0.250000,0.500000]");
    }

    private AppProperties propertiesWithDimension(int dimension) {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setEmbeddingDim(dimension);
        return properties;
    }

    private double l2Norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
