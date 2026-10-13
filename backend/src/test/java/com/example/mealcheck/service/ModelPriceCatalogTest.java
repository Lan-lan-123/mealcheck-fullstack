package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class ModelPriceCatalogTest {
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
