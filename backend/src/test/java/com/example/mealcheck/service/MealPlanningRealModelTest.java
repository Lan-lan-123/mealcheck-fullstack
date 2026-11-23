package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.DietPreferences;
import com.example.mealcheck.dto.MealPlanningDtos.NextMealRequest;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealPlanningRepository;
import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real provider and real tool loop; database inputs and global admission are isolated fixtures. */
@EnabledIfEnvironmentVariable(named="MEALCHECK_LIVE_MODEL_VERIFY", matches="true")
class MealPlanningRealModelTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void configuredModelRunsFourScenarios() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path output = Path.of(Objects.requireNonNull(System.getenv("MEALCHECK_LIVE_MODEL_OUTPUT"))).toAbsolutePath().normalize();
        assertThat(output.startsWith(root.resolve("logs"))).isTrue(); Files.createDirectories(output);
        var settings = new HashMap<String,String>();
        for (String line : Files.readAllLines(root.resolve(".env"))) {
            int split = line.indexOf('=');
            if (split > 0 && !line.stripLeading().startsWith("#")) settings.put(line.substring(0,split).strip(), line.substring(split+1).strip().replaceAll("^[\"']|[\"']$", ""));
        }
        var p = new AppProperties();
        p.getAi().setApiKey(setting(settings,"MEALCHECK_AI_API_KEY"));
        p.getAi().setBaseUrl(setting(settings,"MEALCHECK_AI_BASE_URL"));
        p.getAi().setModel(setting(settings,"MEALCHECK_AI_MODEL"));
        p.getRemoteResilience().setMaxAttempts(1);
        var metrics = new SimpleMeterRegistry();
        var observation = new ApplicationObservability(metrics);
        var admission = mock(AiCallConcurrency.class);
        when(admission.acquire(any())).thenAnswer(ignored -> UUID.randomUUID());
        var evidence = new LinkedHashMap<String,Object>();
        var providerCalls = new ArrayList<Map<String,Object>>();
        var cases = new ArrayList<Map<String,Object>>();
        evidence.put("mode","real-provider-and-real-business-tool-loop");
        evidence.put("configuredModel",p.getAi().getModel()); evidence.put("providerBaseUrl",p.getAi().getBaseUrl());
        evidence.put("fixtures",List.of("empty meal database","default preferences","balanced goal","database quota/admission"));
        evidence.put("scopeLimit","Does not verify HTTP/SSE delivery or database persistence; Docker could not start.");
        evidence.put("providerCalls",providerCalls); evidence.put("cases",cases); evidence.put("passed",false);
        var attempts = new AtomicInteger();
        var transport = new AiHttpTransport(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new RemoteCallGuard(p),mock(AiBudgetService.class),p,json,observation,admission) {
            @Override public HttpResponse<String> send(String operation,String name,HttpRequest request,String body) {
                if (attempts.incrementAndGet()>12) throw new IllegalStateException("Live test exceeded 12-call cap");
                var response=super.send(operation,name,request,body);
                try {
                    var data=json.readTree(response.body()); var item=new LinkedHashMap<String,Object>();
                    var sent=json.readTree(body);
                    item.put("status",response.statusCode()); item.put("requestModel",sent.path("model").asText());
                    item.put("requestToolChoice",sent.path("tool_choice"));
                    item.put("responseModel",data.path("model").asText()); item.put("responseId",data.path("id").asText());
                    item.put("usage",data.path("usage")); item.put("content",data.path("choices").path(0).path("message").path("content"));
                    item.put("toolCalls",data.path("choices").path(0).path("message").path("tool_calls"));
                    providerCalls.add(item); save(output,evidence);
                } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot save safe provider evidence",failure); }
                return response;
            }
        };
        var business=Executors.newSingleThreadExecutor(); var http=Executors.newSingleThreadExecutor();
        try {
            var preferences=mock(DietPreferenceService.class); var goals=mock(UserGoalService.class);
            var meals=mock(MealPlanningRepository.class); var mealService=mock(MealAnalysisService.class);
            when(preferences.get(42L)).thenReturn(DietPreferences.defaults());
            when(goals.effectiveGoal(any(),anyString())).thenReturn("balanced");
            when(meals.recent(eq(42L),any())).thenReturn(List.of()); when(mealService.listSince(any(),anyInt())).thenReturn(List.of());
            var next=new NextMealService(preferences,goals,meals,json);
            var registry=new AssistantFunctionToolRegistry(mock(KnowledgeIndexService.class),mealService,mock(WeeklyReportService.class),
                    goals,next,json,business,business,p,observation);
            var functions=new AssistantFunctionCallingService(p,registry,new AssistantChatModelFactory(p,transport),http);
            var user=new UserAccount(); user.setId(42L); user.setUsername("real-model-fixture");
            String prompt=new AssistantFunctionPromptFactory().buildMealPlanning();
            var history=new ArrayList<AssistantChatMessage>();
            String[] questions={"晚餐在家做，预算25元，30分钟内准备好，推荐下一餐搭配。","改成外卖，预算还是25元",
                    "推荐下一餐搭配","晚餐在家做，预算1元，5分钟内准备好，推荐下一餐搭配"};
            String[] names={"success","follow_up","clarification","no_match"};
            for(int i=0;i<questions.length;i++) {
                System.out.println("Real-model scenario: "+names[i]); long started=System.nanoTime();
                var result=functions.ask(user,prompt,i==1?history:List.of(),questions[i]);
                var item=new LinkedHashMap<String,Object>(); item.put("case",names[i]); item.put("question",questions[i]);
                item.put("answer",json.readTree(result.answer())); item.put("toolTrace",result.toolTrace());
                item.put("durationMs",Duration.ofNanos(System.nanoTime()-started).toMillis()); item.put("passed",false); cases.add(item); save(output,evidence);
                if(i==2) {
                    assertThat(result.toolTrace()).isEmpty(); assertThat(result.answer()).containsAnyOf("请","预算","时间","餐次");
                } else {
                    var expected=new NextMealRequest("DINNER",i==1?"TAKEOUT":"HOME",i==3?1:25,i==3?5:30);
                    var calls=result.toolTrace().stream().filter(call->call.name().equals("recommend_next_meal")).toList();
                    assertThat(calls).singleElement().satisfies(call->{
                        assertThat(call.origin()).isEqualTo("MODEL"); assertThat(call.status()).isEqualTo("SUCCESS");
                        try { assertThat(json.readTree(call.arguments())).isEqualTo(json.valueToTree(expected)); }
                        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
                    });
                    var catalog=next.recommend(new com.example.mealcheck.security.UserPrincipal(user),expected);
                    item.put("catalogVerification",catalog);
                    assertThat(catalog.options().isEmpty()).isEqualTo(i==3);
                    assertThat(calls.get(0).resultSummary()).contains("找到 "+catalog.options().size()+" 套搭配");
                    if(i==3) assertThat(result.answer()).contains(catalog.message());
                }
                if(i==0) {
                    var displayed=json.readTree(result.answer());
                    var text=new StringBuilder(displayed.path("summary").asText());
                    displayed.path("suggestions").forEach(suggestion->text.append("\n- ").append(suggestion.asText()));
                    history.add(new AssistantChatMessage("user",questions[0])); history.add(new AssistantChatMessage("assistant",text.toString()));
                }
                item.put("passed",true); save(output,evidence);
            }
            assertThat(providerCalls).isNotEmpty().allSatisfy(call->{ assertThat(call.get("status")).isEqualTo(200); assertThat(call.get("responseModel")).isNotEqualTo(""); });
            evidence.put("passed",true);
        } finally {
            evidence.put("externalAttempts",attempts.get()); save(output,evidence);
            business.shutdownNow(); http.shutdownNow(); metrics.close();
        }
    }
    private String setting(Map<String,String> values,String key) {
        String value=System.getenv().getOrDefault(key,values.getOrDefault(key,""));
        assertThat(value).as(key+" must be configured").isNotBlank(); return value;
    }
    private void save(Path path,Map<String,Object> value) throws java.io.IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.resolve("real-model-results.json").toFile(),value);
    }
}
