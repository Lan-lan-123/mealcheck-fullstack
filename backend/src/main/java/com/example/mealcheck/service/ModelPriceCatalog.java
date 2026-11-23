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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AiCostSettingsService costSettings;
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
        validate(entries,currency);
    }
    static void validate(List<Price> prices,String currency) {
        if(prices==null || prices.size()>100)throw new IllegalArgumentException("模型价格最多配置 100 项。");
        Set<String> identities=new HashSet<>();
        for(Price price:prices) {
            if(price==null || price.endpoint()==null || price.endpoint().length()>1000)throw new IllegalArgumentException("模型价格接口地址无效。");
            URI endpoint=URI.create(price.endpoint());
            if(endpoint.getHost()==null || !Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getUserInfo()!=null
                    || endpoint.getQuery()!=null || endpoint.getFragment()!=null || price.model()==null || price.model().isBlank() || price.model().length()>200
                    || price.inputCostPerMillion()==null || price.outputCostPerMillion()==null
                    || price.inputCostPerMillion().signum()<0 || price.outputCostPerMillion().signum()<0
                    || price.inputCostPerMillion().compareTo(new BigDecimal("999999999"))>0 || price.outputCostPerMillion().compareTo(new BigDecimal("999999999"))>0
                    || Math.max(0,price.inputCostPerMillion().stripTrailingZeros().scale())>6 || Math.max(0,price.outputCostPerMillion().stripTrailingZeros().scale())>6
                    || !currency.equals(price.currency()) || !identities.add(price.endpoint()+"\n"+price.model()))
                throw new IllegalArgumentException("模型价格须使用统一币种、非负单价（最多六位小数），且接口地址与模型组合不能重复。");
        }
    }
    public Optional<Price> lookup(URI endpoint,String model) {
        if(costSettings!=null)return costSettings.prices().stream().filter(p->URI.create(p.endpoint()).equals(endpoint) && p.model().equals(model)).findFirst();
        var match=entries.stream().filter(p->URI.create(p.endpoint()).equals(endpoint) && p.model().equals(model)).findFirst();
        if(match.isPresent())return match;
        var legacy=properties.getAi().environmentConnection();
        if(legacy.baseUrl()!=null && !legacy.baseUrl().isBlank()
                && endpoint.equals(AiEndpointResolver.chatCompletions(legacy.baseUrl())) && java.util.Objects.equals(legacy.model(),model)
                && (properties.getAi().getInputCostPerMillion().signum()>0 || properties.getAi().getOutputCostPerMillion().signum()>0)) {
            return Optional.of(new Price(endpoint.toString(),model,properties.getAi().getInputCostPerMillion(),properties.getAi().getOutputCostPerMillion(),currency));
        }
        return Optional.empty();
    }
    public Map<String,Object> summary() {
        if(costSettings!=null) {
            var view=costSettings.view();
            return Map.of("currency",currency,"catalogEntries",view.prices().size(),"unknownUsagePolicy","reserve-estimate",
                    "legacyChatPriceConfigured",false,"source",view.source());
        }
        var selected=properties.getAi().connection(); var legacy=properties.getAi().environmentConnection();
        boolean selectedLegacy=java.util.Objects.equals(selected.model(),legacy.model()) && java.util.Objects.equals(selected.baseUrl(),legacy.baseUrl());
        return Map.of("currency",currency,"catalogEntries",entries.size(),"unknownUsagePolicy","reserve-estimate",
                "legacyChatPriceConfigured",selectedLegacy && (properties.getAi().getInputCostPerMillion().signum()>0 || properties.getAi().getOutputCostPerMillion().signum()>0));
    }
}
