package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

/** Shared DB admission by origin, credential and model; never logs credentials or their hashes. */
@Service
public class AiModelAdmission {
    private final AiModelAdmissionStore store;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    public record Lease(UUID id,String scope,String model) { }
    public AiModelAdmission(AiModelAdmissionStore store,AppProperties properties,ObjectMapper mapper,MeterRegistry metrics) {
        this.store=store;this.properties=properties;this.mapper=mapper;this.metrics=metrics;
    }
    @jakarta.annotation.PostConstruct public void validate() {
        var seen=new HashSet<String>();
        for(var policy:properties.getAiModelLimits()) {
            if(policy.getHost().isBlank() || policy.getModel().isBlank() || policy.getConcurrency()<1 || policy.getConcurrency()>100
                    || policy.getRpm()<1 || policy.getRpm()>60000 || policy.getTpm()<0 || !seen.add(policy.getHost()+"/"+policy.getModel()))
                throw new IllegalArgumentException("Invalid or duplicate AI model admission policy");
        }
    }
    public Lease acquire(HttpRequest request,String body,Duration remaining) {
        final String model;
        try {model=mapper.readTree(body).path("model").asText();} catch(Exception invalid) {return null;}
        var policy=properties.getAiModelLimits().stream().filter(p->p.getHost().equalsIgnoreCase(request.uri().getHost()) && p.getModel().equals(model)).findFirst().orElse(null);
        if(policy==null)return null;
        String scope=scope(request,model);
        long tokens=AiRequestTokenEstimate.inputTokens(mapper,body)+properties.getAiBudget().getMaxOutputTokens();
        if(policy.getTpm()>0 && tokens>policy.getTpm())throw new AiFailure.ProviderException("MODEL_RATE_LIMITED",429);
        long started=System.nanoTime(),deadline=started+remaining.toNanos();boolean accepted=false;
        try {
            while(System.nanoTime()<deadline) {
                AiCallContext.checkExecution();
                var admission=store.acquire(scope,policy.getConcurrency(),policy.getRpm(),policy.getTpm(),tokens,
                        Math.max(1000,(deadline-System.nanoTime())/1_000_000)+30000);
                if(admission.id()!=null) {accepted=true;return new Lease(admission.id(),scope,model);}
                metrics.counter("mealcheck.ai.model.admission.waits","model",model,"reason",admission.reason()).increment();
                if("cooldown".equals(admission.reason()))throw new AiFailure.ProviderException("MODEL_RATE_LIMITED",429);
                long left=(deadline-System.nanoTime())/1_000_000;
                if(left<=0)break;
                Thread.sleep(Math.max(1,Math.min(left,Math.min(1000,admission.retryMillis()))));
            }
            throw new RemoteCallGuard.LocalAdmissionDeadlineException("Model admission exceeded operation deadline");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("AI model admission cancelled");
        } catch(org.springframework.dao.DataAccessException unavailable) {throw new AiBudgetService.BudgetUnavailableException();}
        finally {metrics.timer("mealcheck.ai.model.admission.duration","model",model,"outcome",accepted?"accepted":"rejected")
                .record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);}
    }
    public void rateLimited(Lease lease,Duration retryAfter) {
        if(lease==null)return;
        // A long provider Retry-After is respected. No immediate retry consumes additional quota.
        long millis=retryAfter==null?60000:Math.max(1000,Math.min(Duration.ofDays(1).toMillis(),retryAfter.toMillis()));
        try {store.cooldown(lease.scope(),millis);}
        catch(org.springframework.dao.DataAccessException unavailable) {metrics.counter("mealcheck.ai.model.cooldown.deferred").increment();}
    }
    public void release(Lease lease,String response) {
        if(lease==null)return;
        Long actual=null;
        try {
            var usage=mapper.readTree(response).path("usage");
            if(usage.path("total_tokens").isIntegralNumber() && usage.path("total_tokens").asLong(-1)>=0)actual=usage.path("total_tokens").asLong();
            else if(usage.path("prompt_tokens").isIntegralNumber() && usage.path("completion_tokens").isIntegralNumber()
                    && usage.path("prompt_tokens").asLong(-1)>=0 && usage.path("completion_tokens").asLong(-1)>=0)
                actual=Math.addExact(usage.path("prompt_tokens").asLong(),usage.path("completion_tokens").asLong());
        } catch(Exception ignored) { /* Unknown usage keeps the conservative reservation in the rolling window. */ }
        try {store.release(lease.id(),actual);}
        catch(org.springframework.dao.DataAccessException unavailable) {metrics.counter("mealcheck.ai.model.release.deferred").increment();}
    }
    static String scope(HttpRequest request,String model) {
        String origin=request.uri().getScheme()+"://"+request.uri().getAuthority();
        String credential=request.headers().firstValue("Authorization").orElse("");
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    (origin+"\n"+model+"\n"+credential).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
