package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.mock.web.MockMultipartFile;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicitly opt-in real-provider regression; never writes user data or changes production settings. */
public final class FullMealDatasetRegression {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final List<Map<String,Object>> ROWS = new CopyOnWriteArrayList<>(), CALLS = new CopyOnWriteArrayList<>();
    static final ThreadLocal<Map<String,Object>> ACTIVE = new ThreadLocal<>();
    static final ThreadLocal<String> CASE = new ThreadLocal<>();
    static Path root, out;
    static AppProperties props;
    static long budget = 350000, knownInput, knownOutput, unknownAllowance, reserved, nextStart;
    static String state = "PREFLIGHT", started = Instant.now().toString();
    static int unknownCalls;
    static List<String> selectedIds = List.of();
    static volatile boolean budgetStopped;
    static boolean stopOnProviderError;
    static volatile String providerStopReason = "";

    static synchronized void reserve(long bound) {
        if(!providerStopReason.isEmpty()) throw new IllegalStateException("PROVIDER_STOP");
        if (knownInput + knownOutput + unknownAllowance + reserved + bound > budget) {
            budgetStopped=true;
            throw new IllegalStateException("TOKEN_BUDGET_STOP");
        }
        reserved += bound;
    }
    static synchronized void settle(Map<String,Object> call, long bound) {
        reserved -= bound;
        if (Boolean.TRUE.equals(call.get("usageKnown"))) {
            knownInput += ((Number)call.getOrDefault("inputTokens",0L)).longValue();
            knownOutput += ((Number)call.getOrDefault("outputTokens",0L)).longValue();
        } else if (call.containsKey("httpAttempted")) {
            unknownAllowance += bound;
            unknownCalls++;
        }
    }
    static void pace() {
        long wait;
        synchronized (FullMealDatasetRegression.class) {
            long now=System.nanoTime();
            long at=Math.max(now,nextStart); nextStart=at+800_000_000L;
            wait=at-now;
        }
        if (wait>0) try { TimeUnit.NANOSECONDS.sleep(wait); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
    }
    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    static void require(boolean value,String reason) {
        if(!value) throw new IllegalStateException(reason);
    }
    static Map<String,Object> map() { return new ConcurrentHashMap<>(); }
    static synchronized void save() {
        if(out==null) return;
        try {
            var result=new LinkedHashMap<String,Object>();
            result.put("state",state); result.put("startedAtUtc",started); result.put("updatedAtUtc",Instant.now().toString());
            result.put("scope","Current compiled production image validation, recognition, scoring and advice components; real provider; controlled knowledge excerpts; no database/Redis/RabbitMQ/browser verification");
            result.put("datasetKind","synthetic"); result.put("independentlyAnnotated",false);
            result.put("goal","balanced"); result.put("thinkingMode","off"); result.put("maxAttempts",1);
            result.put("model",props==null?"":props.getAi().getModel()); result.put("scoringVersion",MealScoringService.VERSION);
            result.put("tokenBudget",budget); result.put("knownInputTokens",knownInput); result.put("knownOutputTokens",knownOutput);
            result.put("selectedCaseIds",selectedIds);result.put("budgetStopped",budgetStopped);
            result.put("stopOnProviderError",stopOnProviderError); result.put("providerStopReason",providerStopReason);
            result.put("knownTotalTokens",knownInput+knownOutput); result.put("unknownUsageCalls",unknownCalls);
            result.put("unknownUsageAllowance",unknownAllowance); result.put("inFlightReservedTokens",reserved);
            result.put("cases",ROWS); result.put("calls",CALLS);
            Path temp=out.resolve("results.tmp.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),result);
            Files.move(temp,out.resolve("results.json"),StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e) { throw new IllegalStateException("Cannot save regression evidence",e); }
    }

    /** Retain HTTP/error-code evidence even when the production transport throws. */
    static class AuditedClient extends HttpClient {
        final HttpClient delegate=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler) throws java.io.IOException,InterruptedException {
            var response=delegate.send(request,handler); var call=ACTIVE.get();
            if(call!=null) {
                call.put("httpStatus",response.statusCode());
                if(response.statusCode()<200 || response.statusCode()>=300) {
                    String code="";
                    if(response.body() instanceof String body) try {
                        var error=JSON.readTree(body).path("error");
                        String value=error.path("code").asText("");
                        if(value.matches("[A-Za-z0-9_.:-]{1,160}"))code=value;
                    } catch(Exception ignored) { }
                    call.put("providerErrorCode",code);
                    if(stopOnProviderError) providerStopReason="HTTP_"+response.statusCode()+(code.isBlank()?"":":"+code);
                }
            }
            return response;
        }
        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler(){return delegate.cookieHandler();}
        @Override public java.util.Optional<Duration> connectTimeout(){return delegate.connectTimeout();}
        @Override public Redirect followRedirects(){return delegate.followRedirects();}
        @Override public java.util.Optional<java.net.ProxySelector> proxy(){return delegate.proxy();}
        @Override public javax.net.ssl.SSLContext sslContext(){return delegate.sslContext();}
        @Override public javax.net.ssl.SSLParameters sslParameters(){return delegate.sslParameters();}
        @Override public java.util.Optional<java.net.Authenticator> authenticator(){return delegate.authenticator();}
        @Override public Version version(){return delegate.version();}
        @Override public java.util.Optional<java.util.concurrent.Executor> executor(){return delegate.executor();}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException("Synchronous regression only");}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException("Synchronous regression only");}
    }

    static class AuditedTransport extends AiHttpTransport {
        AuditedTransport(AppProperties properties,ApplicationObservability metrics) {
            super(new AuditedClient(),
                    new RemoteCallGuard(properties),mock(AiBudgetService.class),properties,JSON,metrics,mock(AiCallConcurrency.class));
        }
        @Override public HttpResponse<String> send(String operation,String name,HttpRequest request,String body) {
            String prepared=new AiChatRequestPolicy(props,JSON).prepare(operation,request.uri(),body);
            long bound=AiRequestTokenEstimate.inputTokens(JSON,prepared)+props.getAiBudget().getMaxOutputTokens();
            reserve(bound); pace();
            Map<String,Object> call=map();call.put("caseId",CASE.get());call.put("operation",operation);
            call.put("reservedTokenBound",bound);call.put("startedAtUtc",Instant.now().toString());
            long start=System.nanoTime(); ACTIVE.set(call); CALLS.add(call);save();
            try {
                JsonNode sent=JSON.readTree(prepared);
                call.put("requestModel",sent.path("model").asText());call.put("enableThinking",sent.path("enable_thinking").asBoolean());
                call.put("maxCompletionTokens",sent.path("max_completion_tokens").asInt());
                call.put("requestSha256",sha(prepared.getBytes(StandardCharsets.UTF_8)));
                if(!prepared.contains("image_url")) call.put("messages",sent.path("messages"));
                HttpResponse<String> response=super.send(operation,name,request,body);
                call.put("httpStatus",response.statusCode());
                JsonNode responseJson=JSON.readTree(response.body());
                call.put("responseModel",responseJson.path("model").asText());
                call.put("finishReason",responseJson.path("choices").path(0).path("finish_reason").asText());
                call.put("answer",responseJson.path("choices").path(0).path("message").path("content").asText());
                call.put("providerUsage",responseJson.path("usage"));
                call.put("transportSuccess",true);
                return response;
            } catch(Exception e) {
                call.put("transportSuccess",false);call.put("errorType",e.getClass().getSimpleName());
                if(e.getCause()!=null) call.put("causeType",e.getCause().getClass().getSimpleName());
                if(e instanceof RuntimeException runtime)throw runtime;
                throw new IllegalStateException("Response audit failed",e);
            } finally {
                call.put("durationMs",(System.nanoTime()-start)/1_000_000);settle(call,bound);ACTIVE.remove();save();
            }
        }
    }

    static List<KnowledgeSnippet> evidence() throws Exception {
        String source=Files.readString(root.resolve("backend/src/main/resources/knowledge/diet_guides.md"));
        List<KnowledgeSnippet> snippets=new ArrayList<>();
        for(String part:source.split("(?m)^## ")) {
            if(part.isBlank()||part.contains("mealcheck:independent-topics"))continue;
            snippets.add(new KnowledgeSnippet((long)snippets.size()+1,part.lines().findFirst().orElse("饮食知识"),
                    part.substring(0,Math.min(1200,part.length())),0.9));
            if(snippets.size()==3)break;
        }
        return List.copyOf(snippets);
    }
    static void execute(Map<String,Object> row,JsonNode sample,VisionFoodRecognitionService vision,
                        ImageUploadValidationService validation,MealScoringService scoring,AdviceGenerationService advice,
                        List<KnowledgeSnippet> snippets) {
        String id=sample.path("id").asText();CASE.set(id);long start=System.nanoTime();
        row.put("status","RUNNING");save();
        try {
            Path image=root.resolve("tools/datasets/synthetic-meals-100").resolve(sample.path("image").asText());
            var normalized=validation.validateAndNormalize(new MockMultipartFile("image",image.getFileName().toString(),"image/png",Files.readAllBytes(image)));
            row.put("normalizedImage",Map.of("width",normalized.width(),"height",normalized.height(),"bytes",normalized.size(),"sha256",sha(normalized.bytes())));
            var recognized=vision.recognize(normalized);
            row.put("recognition",JSON.valueToTree(recognized)); row.put("decision",recognized.isFoodImage()?"FOOD":"NON_FOOD");
            row.put("foodDecisionMatchesLabel",recognized.isFoodImage()==sample.path("foodImage").asBoolean());
            if(recognized.isFoodImage()) {
                recognized.setUncertainFoods(MealScoringService.uncertainFoods(recognized.getFoods()));
                recognized.setFoods(scoring.acceptedFoods(recognized));
                require(!recognized.getFoods().isEmpty(),"No accepted foods");
                row.put("scoredRecognition",JSON.valueToTree(recognized));
                var evaluation=scoring.evaluate(recognized,"balanced"); row.put("evaluation",evaluation);
                require(evaluation.getScore()>=0&&evaluation.getScore()<=100,"Invalid score");
                var expected=new RecognitionResult();List<FoodItem> expectedFoods=new ArrayList<>();
                for(JsonNode food:sample.path("foods"))expectedFoods.add(new FoodItem(food.path("name").asText(),food.path("category").asText(),1.0,"proposed synthetic label"));
                if(!expectedFoods.isEmpty()) {
                    expected.setFoods(expectedFoods);row.put("labelBasedEvaluation",scoring.evaluate(expected,"balanced"));
                }
                String answer=advice.generate(recognized,evaluation,snippets,"balanced");row.put("advice",answer);
                boolean aiSucceeded=CALLS.stream().anyMatch(c->id.equals(c.get("caseId"))&&"meal-advice".equals(c.get("operation"))&&Boolean.TRUE.equals(c.get("transportSuccess")));
                row.put("adviceFromRealAi",aiSucceeded);
                require(aiSucceeded&&!answer.isBlank()&&!answer.contains("当前展示的是本地规则建议"),"AI advice unavailable or fell back");
            }
            row.put("status","COMPLETED");
        } catch(Exception e) {
            row.put("status","FAILED");row.put("errorType",e.getClass().getSimpleName());
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) if("TOKEN_BUDGET_STOP".equals(cause.getMessage())) {
                budgetStopped=true;row.put("status","BUDGET_STOPPED");break;
            }
            // Advice may catch a budget rejection and return a local fallback.
            if(budgetStopped)row.put("status","BUDGET_STOPPED");
            if(!providerStopReason.isEmpty())row.put("status","PROVIDER_STOPPED");
            if(e instanceof RecognitionFailureException failure)row.put("decision",failure.code());
            if(e.getCause()!=null)row.put("causeType",e.getCause().getClass().getSimpleName());
        } finally {
            row.put("durationMs",(System.nanoTime()-start)/1_000_000);CASE.remove();save();
            synchronized(FullMealDatasetRegression.class) {
                System.out.println("CASE "+id+" status="+row.get("status")+" decision="+row.getOrDefault("decision","ERROR")+
                        " knownTokens="+(knownInput+knownOutput)+" unknownAllowance="+unknownAllowance);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();boolean run=false;
        for(int i=0;i<args.length;i++)if(args[i].equals("--run"))run=true;
            else if(args[i].equals("--stop-on-provider-error"))stopOnProviderError=true;
            else options.put(args[i],args[++i]);
        root=Path.of(options.getOrDefault("--root",".")).toAbsolutePath().normalize();
        out=Path.of(options.getOrDefault("--out",root.resolve("loadtests/results/full100-20261016").toString())).toAbsolutePath().normalize();
        require(out.startsWith(root),"Evidence directory must stay inside the project");Files.createDirectories(out);
        budget=Long.parseLong(options.getOrDefault("--budget","350000"));
        require(budget>0&&budget<=350000,"Budget must be 1..350000 tokens");
        if(run)require(!Files.exists(out.resolve("results.json")),"Existing run evidence must not be overwritten");
        JsonNode dataset=JSON.readTree(root.resolve("tools/datasets/synthetic-meals-100/dataset.json").toFile());
        require(dataset.path("cases").size()==100&&dataset.path("datasetKind").asText().equals("synthetic"),"Unexpected dataset");
        Set<String> ids=new HashSet<>();
        for(JsonNode sample:dataset.path("cases")) {
            String id=sample.path("id").asText();require(ids.add(id),"Duplicate fixture");
            Path image=root.resolve("tools/datasets/synthetic-meals-100").resolve(sample.path("image").asText()).normalize();
            require(image.startsWith(root.resolve("tools/datasets/synthetic-meals-100/images")),"Invalid fixture path");
            require(sha(Files.readAllBytes(image)).equals(sample.path("sha256").asText()),"Image hash mismatch: "+id);
        }
        System.out.println("PREFLIGHT images=100 sha256=100/100 tokenBudget="+budget+" run="+run);
        List<JsonNode> selected=new ArrayList<>();
        if(options.containsKey("--cases")) {
            selectedIds=List.of(options.get("--cases").split(","));
            require(new HashSet<>(selectedIds).size()==selectedIds.size(),"Duplicate selected case");
            for(String id:selectedIds) {
                require(ids.contains(id),"Unknown selected case: "+id);
                for(JsonNode sample:dataset.path("cases"))if(id.equals(sample.path("id").asText()))selected.add(sample);
            }
            require("1".equals(options.getOrDefault("--workers","2")),"Prioritized partial runs require one worker");
        } else dataset.path("cases").forEach(selected::add);
        if(!run)return;
        Map<String,String> env=new HashMap<>();
        for(String line:Files.readAllLines(root.resolve(".env"))) {
            if(line.isBlank()||line.strip().startsWith("#")||!line.contains("="))continue;
            int at=line.indexOf('=');env.put(line.substring(0,at).strip(),line.substring(at+1).strip().replaceAll("^[\"']|[\"']$",""));
        }
        props=new AppProperties();props.getAi().setApiKey(env.get("MEALCHECK_AI_API_KEY"));
        props.getAi().setBaseUrl(env.get("MEALCHECK_AI_BASE_URL"));props.getAi().setModel(options.getOrDefault("--model",env.get("MEALCHECK_AI_MODEL")));
        require(AiChatRequestPolicy.controlledQwen(props.getAi().getModel()),"Unverified model token policy");
        props.getAi().setVisionThinkingMode("off");props.getAi().setAdviceThinkingMode("off");
        props.getAiBudget().setMaxOutputTokens(2048);props.getAi().setTimeoutSeconds(60);props.getRemoteResilience().setMaxAttempts(1);
        var metrics=mock(ApplicationObservability.class);
        doAnswer(i->{var call=ACTIVE.get();if(call!=null){call.put("inputTokens",i.getArgument(1,Long.class));call.put("outputTokens",i.getArgument(2,Long.class));}return null;})
                .when(metrics).recordAiUsage(anyString(),anyLong(),anyLong(),anyDouble());
        doAnswer(i->{var call=ACTIVE.get();if(call!=null)call.put("usageKnown",i.getArgument(1,Boolean.class));return null;})
                .when(metrics).recordAiUsageAvailability(anyString(),anyBoolean(),anyBoolean());
        doAnswer(i->{var call=ACTIVE.get();if(call!=null){call.put("httpAttempted",true);call.put("httpSuccess",i.getArgument(2,Boolean.class));}return null;})
                .when(metrics).recordAiHttpAttempt(anyString(),anyLong(),anyBoolean(),anyBoolean());
        var transport=new AuditedTransport(props,metrics);
        var client=new AiChatClient(props,JSON,mock(AiStatusService.class),metrics,transport);require(client.isConfigured(),"Missing AI configuration");
        var vision=new VisionFoodRecognitionService(client,JSON);var validation=new ImageUploadValidationService(props);
        var scoring=new MealScoringService();var advice=new AdviceGenerationService(client);var snippets=evidence();
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("evidence-snippets.json").toFile(),snippets);
        Files.copy(root.resolve("tools/datasets/synthetic-meals-100/dataset.json"),out.resolve("dataset-snapshot.json"));
        state="RUNNING";List<Future<?>> futures=new ArrayList<>();
        int workers=Integer.parseInt(options.getOrDefault("--workers","2"));require(workers>=1&&workers<=2,"Workers must be 1..2");
        require(!stopOnProviderError || workers==1,"Provider-stop runs require one worker");
        var executor=Executors.newFixedThreadPool(workers);
        try {
            for(JsonNode sample:selected) {
                Map<String,Object> row=map();row.put("id",sample.path("id").asText());row.put("scene",sample.path("scene").asText());
                row.put("expected",sample);row.put("status","PENDING");ROWS.add(row);
                futures.add(executor.submit(()->{
                    if(budgetStopped){row.put("status","NOT_RUN_BUDGET");save();return;}
                    if(!providerStopReason.isEmpty()){row.put("status","NOT_RUN_PROVIDER_STOP");save();return;}
                    execute(row,sample,vision,validation,scoring,advice,snippets);
                }));
            }
            for(Future<?> future:futures)future.get();state="FINISHED";
        } finally {executor.shutdownNow();transport.stopStreamDeadline();save();}
        System.out.println("FINISHED cases="+ROWS.size()+" completed="+ROWS.stream().filter(r->"COMPLETED".equals(r.get("status"))).count()+
                " knownTokens="+(knownInput+knownOutput)+" unknownCalls="+unknownCalls+" unknownAllowance="+unknownAllowance);
    }
}
