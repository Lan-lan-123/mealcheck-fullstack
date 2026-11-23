package com.example.mealcheck.service;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Loopback only: verifies rejection evidence and no further admission without an API key. */
public final class ProviderStopSelfTest {
    public static void main(String[] args) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] body="{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try {
            var call=new ConcurrentHashMap<String,Object>();
            FullMealDatasetRegression.ACTIVE.set(call);
            FullMealDatasetRegression.stopOnProviderError=true;
            new FullMealDatasetRegression.AuditedClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).GET().build(),HttpResponse.BodyHandlers.ofString());
            FullMealDatasetRegression.require(call.get("httpStatus").equals(403),"Missing rejection status");
            FullMealDatasetRegression.require("AllocationQuota.FreeTierOnly".equals(call.get("providerErrorCode")),"Missing quota code");
            boolean rejected=false;
            try {FullMealDatasetRegression.reserve(100);}catch(IllegalStateException error){rejected="PROVIDER_STOP".equals(error.getMessage());}
            FullMealDatasetRegression.require(rejected && FullMealDatasetRegression.reserved==0,"A call was admitted after rejection");
            System.out.println("PASS loopback rejection evidence; subsequent admission stopped; no provider calls");
        } finally {FullMealDatasetRegression.ACTIVE.remove();server.stop(0);}
    }
}
