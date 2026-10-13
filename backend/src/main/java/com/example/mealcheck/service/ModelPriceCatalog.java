package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;

/** Exact endpoint/model matching prevents unrelated providers being billed with a chat model's price. */
@Service
public class ModelPriceCatalog {
    public record Price(String endpoint,String model,BigDecimal inputCostPerMillion,BigDecimal outputCostPerMillion,String currency) { }
    private final AppProperties properties;
    private final List<Price> entries;
    private final String currency;
    @org.springframework.beans.factory.annotation.Autowired
    public ModelPriceCatalog(AppProperties properties,ObjectMapper mapper,Environment environment) {
        this(properties,mapper,environment.getProperty("mealcheck.ai-pricing.currency","CNY"),
                environment.getProperty("mealcheck.ai-pricing.catalog-json","[]"));
    }
    ModelPriceCatalog(AppProperties properties,ObjectMapper mapper,String currency,String json) {
        this.properties=properties;this.currency=currency;
        if(currency==null || !currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid AI cost currency");
        try { entries=List.of(mapper.readValue(json,Price[].class)); }
        catch(Exception error){throw new IllegalArgumentException("Invalid AI price catalogue",error);}
        Set<String> identities=new HashSet<>();
        for(Price price:entries) {
            URI endpoint=URI.create(price.endpoint());
            if(endpoint.getHost()==null || !Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getUserInfo()!=null
                    || endpoint.getQuery()!=null || endpoint.getFragment()!=null || price.model()==null || price.model().isBlank()
                    || price.inputCostPerMillion()==null || price.outputCostPerMillion()==null
                    || price.inputCostPerMillion().signum()<0 || price.outputCostPerMillion().signum()<0
                    || !currency.equals(price.currency()) || !identities.add(price.endpoint()+"\n"+price.model()))
                throw new IllegalArgumentException("Invalid or duplicate AI model price");
        }
    }
    public Optional<Price> lookup(URI endpoint,String model) {
        var match=entries.stream().filter(p->URI.create(p.endpoint()).equals(endpoint) && p.model().equals(model)).findFirst();
        if(match.isPresent())return match;
        if(properties.getAi().getBaseUrl()!=null && !properties.getAi().getBaseUrl().isBlank()
                && endpoint.equals(AiEndpointResolver.chatCompletions(properties.getAi().getBaseUrl())) && properties.getAi().getModel().equals(model)
                && (properties.getAi().getInputCostPerMillion().signum()>0 || properties.getAi().getOutputCostPerMillion().signum()>0)) {
            return Optional.of(new Price(endpoint.toString(),model,properties.getAi().getInputCostPerMillion(),properties.getAi().getOutputCostPerMillion(),currency));
        }
        return Optional.empty();
    }
    public Map<String,Object> summary() {
        return Map.of("currency",currency,"catalogEntries",entries.size(),"unknownUsagePolicy","reserve-estimate",
                "legacyChatPriceConfigured",properties.getAi().getInputCostPerMillion().signum()>0 || properties.getAi().getOutputCostPerMillion().signum()>0);
    }
}
