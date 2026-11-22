package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class ModelPriceCatalogTest {
    @Test void webpagePriceValidationRejectsNullEntriesExcessPrecisionAndCredentialBearingUrls() {
        for(String json:java.util.List.of("[null]",
                "[{\"endpoint\":\"https://user:secret@provider.test/v1/chat/completions\",\"model\":\"m\",\"inputCostPerMillion\":1,\"outputCostPerMillion\":0,\"currency\":\"CNY\"}]",
                "[{\"endpoint\":\"https://provider.test/v1/chat/completions\",\"model\":\"m\",\"inputCostPerMillion\":0.0000001,\"outputCostPerMillion\":0,\"currency\":\"CNY\"}]"))
            assertThatThrownBy(()->new ModelPriceCatalog(new AppProperties(),new ObjectMapper(),"CNY",json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void changingTheActiveModelNeverReusesTheOldModelsLegacyPrice() {
        var properties=new AppProperties();
        properties.getAi().setModel("old-model"); properties.getAi().setBaseUrl("https://provider.test/v1");
        properties.getAi().setInputCostPerMillion(java.math.BigDecimal.TEN);
        var catalog=new ModelPriceCatalog(properties,new ObjectMapper(),"CNY","[]");
        properties.getAi().overrideConnection(new AppProperties.Ai.Connection("key","https://provider.test/v1","new-model"));
        assertThat(catalog.lookup(URI.create("https://provider.test/v1/chat/completions"),"new-model")).isEmpty();
        assertThat(catalog.lookup(URI.create("https://provider.test/v1/chat/completions"),"old-model")).isPresent();
        assertThat(catalog.summary().get("legacyChatPriceConfigured")).isEqualTo(false);
    }
    @Test void pricesMatchBothEndpointAndModelAndLegacyZeroRemainsUnconfigured() {
        var catalog=new ModelPriceCatalog(new AppProperties(),new ObjectMapper(),"CNY","""
                [{"endpoint":"https://provider.example/v1/embeddings","model":"vector-model","inputCostPerMillion":1.5,"outputCostPerMillion":0,"currency":"CNY"},
                 {"endpoint":"https://provider.example/v1/chat/completions","model":"free-model","inputCostPerMillion":0,"outputCostPerMillion":0,"currency":"CNY"}]
                """);
        assertThat(catalog.lookup(URI.create("https://provider.example/v1/embeddings"),"vector-model")).isPresent();
        assertThat(catalog.lookup(URI.create("https://other.example/v1/embeddings"),"vector-model")).isEmpty();
        assertThat(catalog.lookup(URI.create("https://provider.example/v1/embeddings"),"different-model")).isEmpty();
        assertThat(catalog.lookup(URI.create("https://provider.example/v1/chat/completions"),"free-model")).isPresent();
    }
    @Test void mixedCurrenciesAndDuplicatePriceIdentitiesFailStartup() {
        String price="{\"endpoint\":\"https://provider.example/v1/embeddings\",\"model\":\"vector-model\",\"inputCostPerMillion\":1,\"outputCostPerMillion\":0,\"currency\":\"USD\"}";
        assertThatThrownBy(()->new ModelPriceCatalog(new AppProperties(),new ObjectMapper(),"CNY","["+price+"]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ModelPriceCatalog(new AppProperties(),new ObjectMapper(),"USD","["+price+","+price+"]")).isInstanceOf(IllegalArgumentException.class);
    }
}
