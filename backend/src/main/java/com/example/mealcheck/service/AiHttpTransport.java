package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** One retry/budget boundary shared by direct HTTP and LangChain4j clients. */
@Service
public class AiHttpTransport {
    private final HttpClient client;
    private final RemoteCallGuard guard;
    private final AiBudgetService budgets;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final ApplicationObservability observability;
    private final AiCallConcurrency concurrency;
    public AiHttpTransport(HttpClient client,RemoteCallGuard guard,AiBudgetService budgets,AppProperties properties,ObjectMapper mapper,ApplicationObservability observability,AiCallConcurrency concurrency) {
        this.client=client;this.guard=guard;this.budgets=budgets;this.properties=properties;this.mapper=mapper;this.observability=observability;
        this.concurrency=concurrency;
    }
    public HttpResponse<String> send(String name,HttpRequest original,String body) {
        return send(name,name,original,body);
    }
    public HttpResponse<String> send(String operation,String name,HttpRequest original,String body) {
        String capped=capOutput(original.uri(),body);
        BigDecimal inputPrice=BigDecimal.ZERO,outputPrice=BigDecimal.ZERO;
        if (priced(original.uri(),capped)) {
            inputPrice=properties.getAi().getInputCostPerMillion();outputPrice=properties.getAi().getOutputCostPerMillion();
        }
        final BigDecimal in=inputPrice,out=outputPrice;
        BigDecimal estimate=cost(in,out,capped==null?0:capped.length(),maxOutputTokens());
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        return guard.execute(name,original.timeout().orElse(Duration.ofSeconds(60)),remaining->{
            AiCallContext.checkExecution();
            if (Thread.currentThread().isInterrupted()) throw new RemoteCallGuard.RemoteCallRejectedException("AI call interrupted");
            long started=System.nanoTime();
            var permit=concurrency.acquire(remaining);
            AiBudgetStore.Reservation reservation=null;
            BigDecimal actual=null;
            long httpStarted=0;
            boolean success=false;
            try {
                reservation=budgets.reserve(estimate);
                var builder=HttpRequest.newBuilder(original.uri());
                original.headers().map().forEach((key,values)->values.forEach(value->builder.header(key,value)));
                Duration afterReservation=remaining.minusNanos(System.nanoTime()-started);
                if (afterReservation.isNegative() || afterReservation.isZero())
                    throw new RemoteCallGuard.RemoteCallDeadlineException("AI deadline expired while reserving budget");
                builder.method(original.method(),capped==null?original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()):HttpRequest.BodyPublishers.ofString(capped));
                AiCallContext.checkExecution();
                budgets.dispatched(reservation);
                Duration afterDispatch=remaining.minusNanos(System.nanoTime()-started);
                if (afterDispatch.isNegative() || afterDispatch.isZero())
                    throw new RemoteCallGuard.RemoteCallDeadlineException("AI deadline expired before dispatch");
                httpStarted=System.nanoTime();attempts.incrementAndGet();
                HttpResponse<String> response=client.send(builder.timeout(afterDispatch).build(),HttpResponse.BodyHandlers.ofString());
                actual=actualCost(response.body(),in,out);
                recordUsage(operation,response.body(),actual,in.signum()>0 && out.signum()>0);
                int status=response.statusCode();
                if (status==429 || status>=500)
                    throw new RemoteCallGuard.RetryableRemoteCallException(status,RemoteCallGuard.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now()));
                if (status<200 || status>=300) throw new RemoteCallGuard.NonRetryableRemoteCallException("AI provider rejected request: HTTP "+status);
                success=true;
                return response;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw new RemoteCallGuard.RemoteCallRejectedException("AI request interrupted");
            } catch (java.io.IOException network) { throw new IllegalStateException("AI transport failed",network); }
            finally {
                try { budgets.settle(reservation,actual); }
                finally { concurrency.release(permit); }
                if (httpStarted!=0) observability.recordAiHttpAttempt(operation,System.nanoTime()-httpStarted,success,attempts.get()>1);
            }
        });
    }
    private String capOutput(URI uri,String body) {
        if (body==null || !uri.getPath().endsWith("/chat/completions")) return body;
        try {
            ObjectNode node=(ObjectNode)mapper.readTree(body);
            String field=node.has("max_completion_tokens")?"max_completion_tokens":"max_tokens";
            int requested=node.path(field).asInt(maxOutputTokens());
            node.put(field,Math.max(1,Math.min(maxOutputTokens(),requested)));
            return mapper.writeValueAsString(node);
        } catch (Exception invalid) { throw new RemoteCallGuard.NonRetryableRemoteCallException("Invalid AI request JSON"); }
    }
    private boolean priced(URI uri,String body) {
        try {
            return properties.getAi().getInputCostPerMillion().signum()>0 && properties.getAi().getOutputCostPerMillion().signum()>0
                    && uri.equals(AiEndpointResolver.chatCompletions(properties.getAi().getBaseUrl()))
                    && properties.getAi().getModel().equals(mapper.readTree(body).path("model").asText());
        } catch (RuntimeException | java.io.IOException ignored) { return false; }
    }
    private int maxOutputTokens() { return Math.max(1,Math.min(32768,properties.getAiBudget().getMaxOutputTokens())); }
    private BigDecimal actualCost(String body,BigDecimal in,BigDecimal out) {
        if (in.signum()<=0 || out.signum()<=0) return null;
        try {
            var usage=mapper.readTree(body).path("usage");
            if (!usage.has("prompt_tokens") || !usage.has("completion_tokens")) return null;
            long input=usage.path("prompt_tokens").asLong(-1),output=usage.path("completion_tokens").asLong(-1);
            return input<0 || output<0 ? null : cost(in,out,input,output);
        } catch (Exception ignored) { return null; }
    }
    private void recordUsage(String operation,String body,BigDecimal actual,boolean priced) {
        try {
            var usage=mapper.readTree(body).path("usage");
            boolean known=usage.has("prompt_tokens") && usage.has("completion_tokens")
                    && usage.path("prompt_tokens").asLong(-1)>=0 && usage.path("completion_tokens").asLong(-1)>=0;
            observability.recordAiUsageAvailability(operation,known,priced);
            observability.recordAiUsage(operation,known?usage.path("prompt_tokens").asLong():0,
                    known?usage.path("completion_tokens").asLong():0,actual==null?0:actual.doubleValue());
        } catch (java.io.IOException | RuntimeException invalid) { observability.recordAiUsageAvailability(operation,false,priced); }
    }
    private BigDecimal cost(BigDecimal in,BigDecimal out,long input,long output) {
        return in.multiply(BigDecimal.valueOf(input)).add(out.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.CEILING);
    }

    public dev.langchain4j.http.client.HttpClientBuilder langchainBuilder(String name) {
        return new dev.langchain4j.http.client.HttpClientBuilder() {
            private Duration connect=Duration.ofSeconds(10),read=Duration.ofSeconds(60);
            public Duration connectTimeout() { return connect; }
            public dev.langchain4j.http.client.HttpClientBuilder connectTimeout(Duration value) { connect=value;return this; }
            public Duration readTimeout() { return read; }
            public dev.langchain4j.http.client.HttpClientBuilder readTimeout(Duration value) { read=value;return this; }
            public dev.langchain4j.http.client.HttpClient build() {
                Duration attemptTimeout=read;
                return new dev.langchain4j.http.client.HttpClient() {
                    public dev.langchain4j.http.client.SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
                        var builder=HttpRequest.newBuilder(URI.create(request.url())).timeout(attemptTimeout);
                        request.headers().forEach((key,values)->values.forEach(value->builder.header(key,value)));
                        var original=builder.method(request.method().name(),request.body()==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(request.body())).build();
                        var response=send(name,original,request.body());
                        return dev.langchain4j.http.client.SuccessfulHttpResponse.builder().statusCode(response.statusCode()).headers(response.headers().map()).body(response.body()).build();
                    }
                    public void execute(dev.langchain4j.http.client.HttpRequest request,dev.langchain4j.http.client.sse.ServerSentEventParser parser,dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                        throw new UnsupportedOperationException("Streaming AI transport is not configured");
                    }
                };
            }
        };
    }
}
