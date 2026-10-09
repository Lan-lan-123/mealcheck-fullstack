package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiHttpTransportTest {
    @Test void lostExecutionLeasePreventsPhysicalCallsAndBudgetReservation() {
        try (var scope=AiCallContext.systemOperation(Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("lost lease");
        })) {
            assertThatThrownBy(() -> transport.send("lease",request(),body)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(calls).hasValue(0);
        verify(budgets,never()).reserve(any());
    }
    HttpServer server;
    final AtomicInteger calls=new AtomicInteger();
    final ObjectMapper mapper=new ObjectMapper();
    final AiBudgetService budgets=mock(AiBudgetService.class);
    AppProperties properties;
    AiHttpTransport transport;
    URI endpoint;
    String sentBody;
    java.util.concurrent.ExecutorService serverExecutor;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverExecutor=java.util.concurrent.Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions");
        properties=new AppProperties();
        properties.getAi().setBaseUrl(endpoint.resolve("/v1").toString());properties.getAi().setModel("test-model");properties.getAi().setApiKey("test-key");
        properties.getAi().setInputCostPerMillion(BigDecimal.ONE);properties.getAi().setOutputCostPerMillion(new BigDecimal("2"));
        properties.getRemoteResilience().setInitialBackoffMillis(10);
        transport=new AiHttpTransport(HttpClient.newHttpClient(),new RemoteCallGuard(properties),budgets,properties,mapper,mock(ApplicationObservability.class), mock(AiCallConcurrency.class));
    }
    @AfterEach void stop() { server.stop(0);serverExecutor.shutdownNow(); }
    void respond(int status,String retryAfter,boolean transientFirst) {
        server.createContext("/v1/chat/completions",exchange->{
            int count=calls.incrementAndGet();
            sentBody=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            int code=transientFirst && count>1 ? 200:status;
            if (retryAfter!=null) exchange.getResponseHeaders().set("Retry-After",retryAfter);
            byte[] data=(code==200?"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}":"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code,data.length);exchange.getResponseBody().write(data);exchange.close();
        });server.start();
    }
    HttpRequest request() { return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(); }
    final String body="{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":999999}";
    @Test void retriesReserveEachAttemptAndSettleKnownUsage() throws Exception {
        var reservation=new AiBudgetStore.Reservation(java.util.UUID.randomUUID(),"user:1",LocalDate.now(),new BigDecimal("0.02"));
        when(budgets.reserve(any())).thenReturn(reservation);
        respond(429,"0",true);
        assertThat(transport.send("test",request(),body).statusCode()).isEqualTo(200);
        assertThat(calls).hasValue(2);
        assertThat(mapper.readTree(sentBody).path("max_tokens").asInt()).isEqualTo(2048);
        verify(budgets,times(2)).reserve(any());verify(budgets,times(2)).dispatched(reservation);
        verify(budgets).settle(reservation,null);verify(budgets).settle(eq(reservation),eq(new BigDecimal("0.000020")));
    }
    @Test void providerRetryDelayBeyondDeadlineStopsWithoutAnotherAttempt() {
        respond(429,"120",false);
        try (var scope=AiCallContext.open(1L,Duration.ofSeconds(1))) {
            assertThatThrownBy(()->transport.send("test",request(),body)).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        }
        assertThat(calls).hasValue(1);
    }
    @Test void exhaustedBudgetNeverContactsProvider() {
        respond(200,null,false);
        when(budgets.reserve(any())).thenThrow(new AiBudgetService.BudgetExceededException(100,10));
        assertThatThrownBy(()->transport.send("test",request(),body)).isInstanceOf(AiBudgetService.BudgetExceededException.class);
        assertThat(calls).hasValue(0);verify(budgets,times(1)).reserve(any());verify(budgets,never()).dispatched(any());
    }
    @Test void langchainUsesSameTransportAndDisablesNestedRetries() {
        respond(400,null,false);
        var model=new AssistantChatModelFactory(properties,transport).create(Duration.ofSeconds(5));
        assertThatThrownBy(()->model.chat("hello")).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);verify(budgets,times(1)).reserve(any());
    }
    @Test void langchainSuccessAlsoCapsOutputAndSettlesUsage() throws Exception {
        respond(200,null,false);
        var model=new AssistantChatModelFactory(properties,transport).create(Duration.ofSeconds(5));
        assertThat(model.chat("hello")).isEqualTo("ok");
        assertThat(mapper.readTree(sentBody).path("max_tokens").asInt()).isEqualTo(2048);
        verify(budgets).settle(isNull(),eq(new BigDecimal("0.000020")));
    }
    @Test void socketTimeoutUsesRemainingOperationDeadline() throws Exception {
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();
            try { new java.util.concurrent.CountDownLatch(1).await(2,java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });server.start();
        long start=System.nanoTime();
        try (var scope=AiCallContext.open(1L,Duration.ofMillis(150))) {
            assertThatThrownBy(()->transport.send("timeout",request(),body)).isInstanceOf(RuntimeException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls).hasValue(1);
    }
}
