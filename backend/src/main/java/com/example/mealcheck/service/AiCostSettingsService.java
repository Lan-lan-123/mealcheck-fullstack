package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared policies are read from the database; edits never reset today's accounting. */
@Service
public class AiCostSettingsService {
    public record Update(@Min(0) long version,
            @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer=9,fraction=6) BigDecimal globalDailyCost,
            @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer=9,fraction=6) BigDecimal userDailyCost,
            @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer=9,fraction=6) BigDecimal systemDailyCost,
            @NotNull @Size(max=100) List<ModelPriceCatalog.Price> prices) { }
    public record Version(@Min(0) long version) { }
    public record PersonalUpdate(@Min(0) long version,
            @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer=9,fraction=6) BigDecimal dailyCost) { }
    public record View(long version, boolean enabled, String currency, String source, BigDecimal globalDailyCost,
            BigDecimal userDailyCost, BigDecimal systemDailyCost, List<ModelPriceCatalog.Price> prices) { }
    public record PersonalView(long version, boolean enabled, String currency, BigDecimal dailyCost,
            BigDecimal administratorDailyCost, BigDecimal effectiveDailyCost) { }
    public record Limits(BigDecimal global, BigDecimal quota) { }
    private record State(long version, BigDecimal global, BigDecimal user, BigDecimal system, String prices) { }
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final Environment environment;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AdminAuditService audit;

    public AiCostSettingsService(JdbcTemplate jdbc, AppProperties properties, ObjectMapper mapper, Environment environment) {
        this.jdbc=jdbc; this.properties=properties; this.mapper=mapper; this.environment=environment;
    }
    private State state(boolean lock) {
        return jdbc.queryForObject("SELECT version,global_daily_cost,user_daily_cost,system_daily_cost,price_catalog_json "
                + "FROM ai_cost_settings WHERE id=1" + (lock ? " FOR SHARE" : ""),
                (rs,n)->new State(rs.getLong(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5)));
    }
    public String currency() { return environment.getProperty("mealcheck.ai-pricing.currency","CNY"); }
    private BigDecimal globalDefault() { return environment.getProperty("mealcheck.ai-budget.global-daily-cost",BigDecimal.class,BigDecimal.ZERO); }
    private BigDecimal choose(BigDecimal value, BigDecimal fallback) { return value==null ? fallback : value; }
    static BigDecimal tighter(BigDecimal administrator, BigDecimal personal) {
        if (personal==null || personal.signum()==0) return administrator;
        return administrator.signum()==0 ? personal : administrator.min(personal);
    }
    private BigDecimal personal(Long userId, boolean lock) {
        var values=jdbc.query("SELECT daily_cost FROM ai_personal_cost_settings WHERE user_id=?"+(lock?" FOR SHARE":""),
                (rs,n)->rs.getBigDecimal(1),userId);
        return values.isEmpty() ? BigDecimal.ZERO : values.get(0);
    }
    // Called inside the reservation transaction. Shared row locks serialize edits against admissions.
    public Limits limits(Long userId, BigDecimal globalFallback, BigDecimal quotaFallback, boolean lock) {
        var state=state(lock);
        BigDecimal quota=choose(userId==null?state.system():state.user(),quotaFallback);
        if (userId!=null) quota=tighter(quota,personal(userId,lock));
        return new Limits(choose(state.global(),globalFallback),quota);
    }
    public List<ModelPriceCatalog.Price> prices() { return prices(state(false)); }
    private List<ModelPriceCatalog.Price> prices(State state) {
        try {
            String json=state.prices()==null ? environment.getProperty("mealcheck.ai-pricing.catalog-json","[]") : state.prices();
            var prices=new ArrayList<>(List.of(mapper.readValue(json,ModelPriceCatalog.Price[].class)));
            if (state.prices()==null) {
                var ai=properties.getAi(); var connection=ai.environmentConnection();
                if (connection.baseUrl()!=null && !connection.baseUrl().isBlank()
                        && (ai.getInputCostPerMillion().signum()>0 || ai.getOutputCostPerMillion().signum()>0)) {
                    var endpoint=AiEndpointResolver.chatCompletions(connection.baseUrl()).toString();
                    if (prices.stream().noneMatch(p->p.endpoint().equals(endpoint) && p.model().equals(connection.model())))
                        prices.add(new ModelPriceCatalog.Price(endpoint,connection.model(),ai.getInputCostPerMillion(),ai.getOutputCostPerMillion(),currency()));
                }
            }
            ModelPriceCatalog.validate(prices,currency());
            return List.copyOf(prices);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("模型价格配置格式无效。"); }
    }
    @Transactional(readOnly=true)
    public View view() { return view(state(false)); }
    private View view(State state) {
        return new View(state.version(),properties.getAiBudget().isEnabled(),currency(),state.prices()==null?"ENVIRONMENT":"ADMIN",
                choose(state.global(),globalDefault()),choose(state.user(),properties.getAiBudget().getDailyCost()),
                choose(state.system(),properties.getAiBudget().getSystemDailyCost()),prices(state));
    }
    private void checkVersion(int changed) {
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"预算配置已变化，请刷新配置后重试。");
    }
    @Transactional
    public View save(Update request, UserPrincipal principal) {
        ModelPriceCatalog.validate(request.prices(),currency());
        String json;
        try { json=mapper.writeValueAsString(request.prices()); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("模型价格配置格式无效。"); }
        checkVersion(jdbc.update("UPDATE ai_cost_settings SET global_daily_cost=?,user_daily_cost=?,system_daily_cost=?,"
                +"price_catalog_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1 AND version=?",
                request.globalDailyCost(),request.userDailyCost(),request.systemDailyCost(),json,request.version()));
        if (audit!=null) audit.log(principal,"UPDATE_AI_COST_SETTINGS","AI_COST_SETTINGS",1L,"修改金额上限与模型价格");
        return view(state(false));
    }
    @Transactional
    public View reset(Version request, UserPrincipal principal) {
        checkVersion(jdbc.update("UPDATE ai_cost_settings SET global_daily_cost=NULL,user_daily_cost=NULL,system_daily_cost=NULL,"
                +"price_catalog_json=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1 AND version=?",request.version()));
        if (audit!=null) audit.log(principal,"RESET_AI_COST_SETTINGS","AI_COST_SETTINGS",1L,"恢复部署金额上限与模型价格");
        return view(state(false));
    }
    @Transactional(readOnly=true)
    public PersonalView personalView(Long userId) {
        var state=state(false);
        var rows=jdbc.queryForList("SELECT version,daily_cost FROM ai_personal_cost_settings WHERE user_id=?",userId);
        var limit=rows.isEmpty()?BigDecimal.ZERO:(BigDecimal)rows.get(0).get("daily_cost");
        var administrator=choose(state.user(),properties.getAiBudget().getDailyCost());
        return new PersonalView(rows.isEmpty()?0:((Number)rows.get(0).get("version")).longValue(),
                properties.getAiBudget().isEnabled(),currency(),limit,administrator,tighter(administrator,limit));
    }
    @Transactional
    public PersonalView savePersonal(Long userId, PersonalUpdate request) {
        state(true); // Same global -> personal lock order as reservations.
        jdbc.update("INSERT INTO ai_personal_cost_settings(user_id) VALUES (?) ON CONFLICT DO NOTHING",userId);
        checkVersion(jdbc.update("UPDATE ai_personal_cost_settings SET daily_cost=?,version=version+1,updated_at=CURRENT_TIMESTAMP "
                +"WHERE user_id=? AND version=?",request.dailyCost(),userId,request.version()));
        return personalView(userId);
    }
}
