package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** One retry/budget boundary shared by direct HTTP and LangChain4j clients. */
@Service
public class AiHttpTransport {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AiHttpTransport.class);
    private final HttpClient client;
    private final RemoteCallGuard guard;
    private final AiBudgetService budgets;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final ApplicationObservability observability;
    private final AiCallConcurrency concurrency;
    private final AiChatRequestPolicy requestPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ModelPriceCatalog priceCatalog;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private AiModelAdmission modelAdmission;
    public AiHttpTransport(HttpClient client,RemoteCallGuard guard,AiBudgetService budgets,AppProperties properties,ObjectMapper mapper,ApplicationObservability observability,AiCallConcurrency concurrency) {
        this.client=client;this.guard=guard;this.budgets=budgets;this.properties=properties;this.mapper=mapper;this.observability=observability;
        this.concurrency=concurrency;
        this.requestPolicy=new AiChatRequestPolicy(properties,mapper);
        this.requestPolicy.validate();
        if(properties.getAiBudget().getMinimumHttpExecutionMillis()<1 || properties.getAiBudget().getMinimumHttpExecutionMillis()>60000)
            throw new IllegalArgumentException("Minimum AI HTTP execution window must be 1..60000ms");
    }
    private Duration admissionWindow(RemoteCallGuard.AttemptBudget budget) {
        Duration minimum=Duration.ofMillis(properties.getAiBudget().getMinimumHttpExecutionMillis());
        if(budget.httpTimeout().compareTo(minimum)<0)
            throw new RemoteCallGuard.LocalAdmissionDeadlineException("Configured HTTP timeout is below minimum execution window");
        Duration left=budget.remaining().minus(minimum);
        if(left.isNegative() || left.isZero())
            throw new RemoteCallGuard.LocalAdmissionDeadlineException("Insufficient AI deadline for admission and HTTP execution");
        return left;
    }
    private Duration dispatchWindow(RemoteCallGuard.AttemptBudget budget) {
        AiCallContext.checkExecution();
        Duration left=budget.httpRemaining();
        if(left.compareTo(Duration.ofMillis(properties.getAiBudget().getMinimumHttpExecutionMillis()))<0)
            throw new RemoteCallGuard.LocalAdmissionDeadlineException("Insufficient AI HTTP execution window after admission");
        return left;
    }
    public HttpResponse<String> send(String name,HttpRequest original,String body) {
        return send(name,name,original,body);
    }
    public HttpResponse<String> send(String operation,String name,HttpRequest original,String body) {
        String capped=requestPolicy.prepare(operation,original.uri(),body);
        BigDecimal inputPrice=BigDecimal.ZERO,outputPrice=BigDecimal.ZERO;
        boolean priceKnown=false;
        if(priceCatalog!=null) {
            try {
                var price=priceCatalog.lookup(original.uri(),mapper.readTree(capped).path("model").asText());
                if(price.isPresent()) { inputPrice=price.get().inputCostPerMillion();outputPrice=price.get().outputCostPerMillion();priceKnown=true; }
            } catch(java.io.IOException | NullPointerException ignored) { }
        } else if (priced(original.uri(),capped)) {
            inputPrice=properties.getAi().getInputCostPerMillion();outputPrice=properties.getAi().getOutputCostPerMillion();
            priceKnown=true;
        }
        final boolean knownPrice=priceKnown;
        final BigDecimal in=inputPrice,out=outputPrice;
        BigDecimal estimate=cost(in,out,AiRequestTokenEstimate.inputTokens(mapper,capped),maxOutputTokens());
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        return guard.executeWithBudget(name,original.timeout().orElse(Duration.ofSeconds(60)),budget->{
            AiCallContext.checkExecution();
            if (Thread.currentThread().isInterrupted()) throw new RemoteCallGuard.RemoteCallRejectedException("AI call interrupted");
            long started=System.nanoTime();
            AiModelAdmission.Lease modelLease=null;
            java.util.UUID permit=null;
            String responseBody=null;
            boolean budgetBoundary=false;
            AiBudgetStore.Reservation reservation=null;
            BigDecimal actual=null;
            long httpStarted=0;
            boolean success=false;
            boolean usageRecorded=false;
            String failureKind="unknown";
            int httpStatus=0;
            AiHttpObservation observation=null;
            String traceId=java.util.UUID.randomUUID().toString();
            try {
                if(modelAdmission!=null)modelLease=modelAdmission.acquire(original,capped,admissionWindow(budget));
                permit=concurrency.acquire(admissionWindow(budget));
                budgetBoundary=true;
                reservation=budgets.reserve(estimate);
                var builder=HttpRequest.newBuilder(original.uri());
                original.headers().map().forEach((key,values)->values.forEach(value->builder.header(key,value)));
                builder.method(original.method(),capped==null?original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()):HttpRequest.BodyPublishers.ofString(capped));
                dispatchWindow(budget);
                budgets.dispatched(reservation);
                Duration afterDispatch=dispatchWindow(budget);
                httpStarted=System.nanoTime();attempts.incrementAndGet();
                if("api.siliconflow.cn".equalsIgnoreCase(original.uri().getHost()) && original.headers().firstValue("X-Trace-Id").isEmpty())builder.header("X-Trace-Id",traceId);
                observation=new AiHttpObservation();
                logRequestShape(operation,capped,traceId);
                HttpResponse<String> response=client.send(builder.timeout(afterDispatch).build(),observation.handler());
                AiCallContext.checkExecution();
                responseBody=response.body();
                httpStatus=response.statusCode();
                actual=knownPrice?actualCost(response.body(),in,out,original.uri().getPath().endsWith("/embeddings")):null;
                recordUsage(operation,response.body(),actual,knownPrice,original.uri().getPath().endsWith("/embeddings"));
                usageRecorded=true;
                int status=response.statusCode();
                var rejection=status>=400?AiFailure.rejected(status,response.body(),mapper):null;
                if(rejection!=null && rejection.code().equals("MODEL_QUOTA_EXHAUSTED"))throw rejection;
                if(status==429 && modelLease!=null) {
                    modelAdmission.rateLimited(modelLease,RemoteCallGuard.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now()));
                    throw new AiFailure.ProviderException("MODEL_RATE_LIMITED",429);
                }
                if (status==429 || status>=500)
                    throw new RemoteCallGuard.RetryableRemoteCallException(status,RemoteCallGuard.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now()));
                if (status<200 || status>=300) throw rejection!=null?rejection:AiFailure.rejected(status,"",mapper);
                rejectTruncatedContent(response.body());
                success=true;
                return response;
            } catch (InterruptedException interrupted) {
                failureKind="interrupted";
                Thread.currentThread().interrupt();throw new RemoteCallGuard.RemoteCallRejectedException("AI request interrupted");
            } catch (java.io.IOException network) {
                failureKind=network instanceof HttpTimeoutException?"timeout":"io";
                throw new IllegalStateException("AI transport failed",network);
            } catch (RuntimeException failure) {
                failureKind=failure instanceof AiFailure.ProviderException p?p.code():httpStatus==429?"provider_429":httpStatus>=500?"provider_5xx":httpStatus>=400?"provider_4xx":"response_or_deadline";
                throw failure;
            }
            finally {
                try { if(budgetBoundary)budgets.settle(reservation,actual); }
                finally {
                    try {
                        try { if(permit!=null)concurrency.release(permit); }
                        finally { if(modelAdmission!=null)modelAdmission.release(modelLease,responseBody); }
                    } finally {
                        if (httpStarted!=0) {
                            if(observation!=null)log.info("AI response observation. operation={}, clientTraceId={}, headersMicros={}, firstBodyByteMicros={}, headersStatus={}, providerTraceId={}, elapsedMicros={}, outcome={}",
                                    operation,traceId,observation.headers,observation.firstByte,observation.status,observation.providerTrace,
                                    (System.nanoTime()-httpStarted)/1000,success?"success":"failure");
                            log.info("AI attempt timing. operation={}, attempt={}, elapsedMicros={}, dispatchWaitMicros={}, outcome={}, status={}",
                                    operation,attempts.get(),(System.nanoTime()-httpStarted)/1000,
                                    (httpStarted-started)/1000,success?"success":"failure",httpStatus);
                            if (!usageRecorded) observability.recordAiUsageAvailability(operation,false,knownPrice);
                            observability.recordAiHttpAttempt(operation,System.nanoTime()-httpStarted,success,attempts.get()>1);
                            if (!success) {
                                observability.recordAiHttpFailure(operation,failureKind);
                                log.warn("AI HTTP attempt failed. operation={}, reason={}, status={}, elapsedMs={}",
                                        operation,failureKind,httpStatus,(System.nanoTime()-httpStarted)/1_000_000);
                            }
                        }
                    }
                }
            }
        });
    }
    private void logRequestShape(String operation,String body,String traceId) {
        try {
            var node=mapper.readTree(body);
            String model=node.path("model").asText("");if(!model.matches("[A-Za-z0-9_./:-]{1,100}"))model="unknown";
            String thinking=node.has("enable_thinking")?Boolean.toString(node.path("enable_thinking").asBoolean()):"provider";
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            log.info("AI request shape. operation={}, clientTraceId={}, model={}, bodyChars={}, messages={}, tools={}, maxTokens={}, thinking={}, stream={}, payloadSha256={}",
                    operation,traceId,model,body.length(),node.path("messages").size(),node.path("tools").size(),
                    node.path("max_tokens").asInt(node.path("max_completion_tokens").asInt(-1)),thinking,node.path("stream").asBoolean(),digest);
        } catch(Exception invalid) {log.info("AI request shape unavailable. operation={}, clientTraceId={}",operation,traceId);}
    }
    private void rejectTruncatedContent(String body) {
        try {
            for (var choice : mapper.readTree(body).path("choices")) {
                if ("length".equals(choice.path("finish_reason").asText()))
                    throw new RemoteCallGuard.NonRetryableRemoteCallException("AI output exceeded configured token limit");
            }
        } catch (java.io.IOException ignored) { /* Response parsing remains the caller's responsibility. */ }
    }
    private boolean priced(URI uri,String body) {
        try {
            var legacy=properties.getAi().environmentConnection();
            return (properties.getAi().getInputCostPerMillion().signum()>0 || properties.getAi().getOutputCostPerMillion().signum()>0)
                    && uri.equals(AiEndpointResolver.chatCompletions(legacy.baseUrl()))
                    && java.util.Objects.equals(legacy.model(),mapper.readTree(body).path("model").asText());
        } catch (RuntimeException | java.io.IOException ignored) { return false; }
    }
    private int maxOutputTokens() { return Math.max(1,Math.min(32768,properties.getAiBudget().getMaxOutputTokens())); }
    private BigDecimal actualCost(String body,BigDecimal in,BigDecimal out,boolean embedding) {
        try {
            var usage=mapper.readTree(body).path("usage");
            if (!usage.has("prompt_tokens") || (!embedding && !usage.has("completion_tokens"))) return null;
            long input=usage.path("prompt_tokens").asLong(-1),output=usage.path("completion_tokens").asLong(embedding?0:-1);
            return input<0 || output<0 ? null : cost(in,out,input,output);
        } catch (Exception ignored) { return null; }
    }
    private void recordUsage(String operation,String body,BigDecimal actual,boolean priced,boolean embedding) {
        try {
            var usage=mapper.readTree(body).path("usage");
            boolean known=usage.has("prompt_tokens") && (embedding || usage.has("completion_tokens"))
                    && usage.path("prompt_tokens").asLong(-1)>=0 && usage.path("completion_tokens").asLong(embedding?0:-1)>=0;
            observability.recordAiUsageAvailability(operation,known,priced);
            observability.recordAiUsage(operation,known?usage.path("prompt_tokens").asLong():0,
                    known?usage.path("completion_tokens").asLong():0,actual==null?0:actual.doubleValue());
            if("diet-assistant-evidence-review".equals(operation)) {
                String finish=mapper.readTree(body).path("choices").path(0).path("finish_reason").asText("");
                if(!java.util.Set.of("stop","length","tool_calls","content_filter").contains(finish))finish="unknown";
                log.info("Assistant review response. chars={}, promptTokens={}, completionTokens={}, finishReason={}",body.length(),
                        known?Math.max(0,usage.path("prompt_tokens").asLong()):-1,
                        known?Math.max(0,usage.path("completion_tokens").asLong()):-1,finish);
            }
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

    /** Consume provider SSE as it arrives. Never retry a stream once response bytes are delivered. */
    public String stream(String name,HttpRequest original,String body,AssistantStreamDelivery delivery) {
        String capped=requestPolicy.prepare(name,original.uri(),body);
        BigDecimal in=BigDecimal.ZERO,out=BigDecimal.ZERO;
        boolean known=false;
        try {
            if(priceCatalog!=null) {
                var price=priceCatalog.lookup(original.uri(),mapper.readTree(capped).path("model").asText());
                if(price.isPresent()) { in=price.get().inputCostPerMillion();out=price.get().outputCostPerMillion();known=true; }
            } else if(priced(original.uri(),capped)) { in=properties.getAi().getInputCostPerMillion();out=properties.getAi().getOutputCostPerMillion();known=true; }
        } catch(java.io.IOException ignored) { }
        final BigDecimal input=in,output=out;
        final boolean priced=known;
        return guard.executeWithBudget(name,original.timeout().orElse(Duration.ofSeconds(60)),budget->{
            delivery.check(); AiCallContext.checkExecution();
            long started=System.nanoTime(); java.util.UUID permit=null;
            AiModelAdmission.Lease modelLease=null;
            long httpStarted=0, httpDeadline=0;
            boolean budgetBoundary=false;
            AiBudgetStore.Reservation reservation=null; BigDecimal actual=null;
            java.io.InputStream stream=null;
            java.util.concurrent.ScheduledFuture<?> expiry=null;
            boolean dispatched=false,success=false; String usage=null;
            long headersMicros=-1,firstContentMicros=-1;
            String clientTrace=java.util.UUID.randomUUID().toString(),providerTrace="unavailable";
            try {
                if(modelAdmission!=null)modelLease=modelAdmission.acquire(original,capped,admissionWindow(budget));
                permit=concurrency.acquire(admissionWindow(budget));
                budgetBoundary=true;
                reservation=budgets.reserve(cost(input,output,AiRequestTokenEstimate.inputTokens(mapper,capped),maxOutputTokens()));
                var builder=HttpRequest.newBuilder(original.uri());
                original.headers().map().forEach((k,values)->values.forEach(v->builder.header(k,v)));
                delivery.check(); dispatchWindow(budget); budgets.dispatched(reservation);
                Duration left=dispatchWindow(budget);
                httpStarted=System.nanoTime(); httpDeadline=Math.min(budget.deadlineNanos(),httpStarted+left.toNanos()); dispatched=true;
                if("api.siliconflow.cn".equalsIgnoreCase(original.uri().getHost()) && original.headers().firstValue("X-Trace-Id").isEmpty())builder.header("X-Trace-Id",clientTrace);
                logRequestShape(name,capped,clientTrace);
                var pending=client.sendAsync(builder.timeout(left).POST(HttpRequest.BodyPublishers.ofString(capped)).build(),HttpResponse.BodyHandlers.ofInputStream());
                delivery.transport(()->pending.cancel(true));
                var response=pending.get(Math.max(1,left.toMillis()),java.util.concurrent.TimeUnit.MILLISECONDS);
                headersMicros=(System.nanoTime()-httpStarted)/1000;
                String returnedTrace=response.headers().firstValue("x-siliconcloud-trace-id").orElse("");
                if(returnedTrace.matches("[A-Za-z0-9_.:/-]{1,160}"))providerTrace=returnedTrace;
                stream=response.body(); var inputStream=stream;
                Runnable close=()->{ try { inputStream.close(); } catch(java.io.IOException ignored) { } };
                delivery.transport(close);
                long deadline=httpDeadline-System.nanoTime();
                expiry=streamDeadline.schedule(close,Math.max(1,deadline),java.util.concurrent.TimeUnit.NANOSECONDS);
                if(response.statusCode()<200 || response.statusCode()>=300) {
                    String errorBody=new String(stream.readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8);
                    var rejection=AiFailure.rejected(response.statusCode(),errorBody,mapper);
                    if(rejection.code().equals("MODEL_QUOTA_EXHAUSTED"))throw rejection;
                    if(response.statusCode()==429 && modelLease!=null) {
                        modelAdmission.rateLimited(modelLease,RemoteCallGuard.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now()));
                        throw new AiFailure.ProviderException("MODEL_RATE_LIMITED",429);
                    }
                    if(response.statusCode()==429 || response.statusCode()>=500)throw new RemoteCallGuard.RetryableRemoteCallException(response.statusCode(),RemoteCallGuard.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now()));
                    throw rejection;
                }
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
                var data=new StringBuilder(); var answer=new StringBuilder(); boolean ended=false,truncated=false;
                String line;
                while((line=reader.readLine())!=null) {
                    delivery.check(); AiCallContext.checkExecution();
                    if(System.nanoTime()>=httpDeadline)throw new RemoteCallGuard.NonRetryableRemoteCallException("AI stream deadline exceeded");
                    if(line.length()>128_000 || data.length()>128_000)throw new RemoteCallGuard.NonRetryableRemoteCallException("AI stream event too large");
                    if(line.startsWith("data:")) { if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading()); }
                    else if(line.isEmpty() && !data.isEmpty()) {
                        String event=data.toString();data.setLength(0);
                        if(event.equals("[DONE]")) { ended=true;break; }
                        var node=mapper.readTree(event);
                        if(node.has("error"))throw new RemoteCallGuard.NonRetryableRemoteCallException("AI provider stream error");
                        if(node.hasNonNull("usage"))usage=event;
                        for(var choice:node.path("choices"))
                            if("length".equals(choice.path("finish_reason").asText()))truncated=true;
                        String delta=node.path("choices").path(0).path("delta").path("content").asText("");
                        if(!delta.isEmpty()) {
                            if(firstContentMicros<0)firstContentMicros=(System.nanoTime()-httpStarted)/1000;
                            answer.append(delta);delivery.modelDelta(delta);
                        }
                    }
                }
                delivery.check();
                actual=priced?actualCost(usage,input,output,false):null;
                if(truncated)throw new RemoteCallGuard.NonRetryableRemoteCallException("AI output exceeded configured token limit");
                if(!ended || answer.isEmpty())throw new RemoteCallGuard.NonRetryableRemoteCallException("AI stream ended without a complete answer");
                success=true; return answer.toString();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("回复已取消");
            } catch(RemoteCallGuard.RetryableRemoteCallException e) { throw e;
            } catch(RemoteCallGuard.NonRetryableRemoteCallException e) { throw e;
            } catch(RemoteCallGuard.RemoteCallRejectedException e) { throw e;
            } catch(Exception e) {
                delivery.check(); throw new RemoteCallGuard.NonRetryableRemoteCallException("AI stream failed: "+e.getClass().getSimpleName());
            } finally {
                if(expiry!=null)expiry.cancel(false);
                if(stream!=null)try { stream.close(); } catch(java.io.IOException ignored) { }
                delivery.transport(null);
                if(dispatched) {
                    log.info("AI stream observation. operation={}, clientTraceId={}, headersMicros={}, firstContentMicros={}, providerTraceId={}, elapsedMicros={}, outcome={}",
                            name,clientTrace,headersMicros,firstContentMicros,providerTrace,(System.nanoTime()-httpStarted)/1000,success?"success":"failure");
                    recordUsage(name,usage,actual,priced,false);
                }
                try { if(budgetBoundary)budgets.settle(reservation,actual); }
                finally {
                    try { if(permit!=null)concurrency.release(permit); }
                    finally { if(modelAdmission!=null)modelAdmission.release(modelLease,usage); }
                }
                if(dispatched)observability.recordAiHttpAttempt(name,System.nanoTime()-httpStarted,success,false);
            }
        });
    }
    private final java.util.concurrent.ScheduledExecutorService streamDeadline=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{
        var thread=new Thread(r,"ai-stream-deadline");thread.setDaemon(true);return thread;
    });
    @jakarta.annotation.PreDestroy void stopStreamDeadline() { streamDeadline.shutdownNow(); }
}
