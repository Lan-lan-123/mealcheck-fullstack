package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Finite public failure codes. Provider bodies/messages and credentials never leave this boundary. */
public final class AiFailure {
    private AiFailure() { }
    public record Detail(String code,String summary,Integer status) { }
    private static final Map<String,String> MESSAGES=Map.of(
            "MODEL_QUOTA_EXHAUSTED","模型服务额度已用尽，请联系管理员恢复额度后再试。",
            "MODEL_ACCESS_DENIED","模型服务访问被拒绝，请联系管理员检查密钥和模型权限。",
            "MODEL_RATE_LIMITED","模型服务请求受限，请稍后再试。",
            "MODEL_TIMEOUT","本次未能在时限内完成证据核验，请稍后重试或缩小问题范围。",
            "MODEL_CONNECTION_FAILED","模型服务连接异常，暂时无法完成回答，请稍后重试。",
            "MODEL_RESPONSE_INVALID","模型返回格式无效，暂时无法完成证据核验，请稍后重试。",
            "MODEL_SERVICE_UNAVAILABLE","模型服务暂不可用，无法完成证据核验，请稍后再试。",
            "AI_BUDGET_EXCEEDED","本次可用 AI 预算已用尽，请在预算恢复后再试。");
    public static boolean known(String code) { return MESSAGES.containsKey(code); }
    public static Detail detail(String code,Integer status) {
        String safe=known(code)?code:"MODEL_SERVICE_UNAVAILABLE";
        return new Detail(safe,MESSAGES.get(safe),status);
    }
    public static final class ProviderException extends RemoteCallGuard.NonRetryableRemoteCallException {
        private final String code;
        ProviderException(String code,int status) { super(code,status);this.code=code; }
        public String code() {return code;}
    }
    public static final class InvalidResponseException extends RuntimeException {
        public InvalidResponseException() {super("Model response did not satisfy the review protocol");}
    }
    static ProviderException rejected(int status,String body,ObjectMapper mapper) {
        String providerCode="";
        try {
            var data=mapper.readTree(body);
            providerCode=data.path("error").path("code").asText(data.path("code").asText(""))
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
        } catch(Exception ignored) { }
        boolean quota=Set.of("insufficientquota","quotaexhausted","arrearage","allocationquotafreetieronly",
                "throttlingallocationquota","accountoverdue","balanceinsufficient").contains(providerCode);
        String code=quota || status==402?"MODEL_QUOTA_EXHAUSTED":status==401 || status==403?"MODEL_ACCESS_DENIED"
                :status==429?"MODEL_RATE_LIMITED":"MODEL_SERVICE_UNAVAILABLE";
        return new ProviderException(code,status);
    }
    public static Detail classify(Throwable failure) {
        var causes=new ArrayList<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(var cause=failure;cause!=null && seen.add(cause);cause=cause.getCause())causes.add(cause);
        for(var cause:causes)if(cause instanceof ProviderException p)return detail(p.code(),p.status());
        for(var cause:causes) {
            if(cause instanceof InvalidResponseException || cause instanceof AiChatClient.InvalidResponseException
                    || cause instanceof com.fasterxml.jackson.core.JsonProcessingException)return detail("MODEL_RESPONSE_INVALID",null);
            if(cause instanceof AiBudgetService.BudgetExceededException)return detail("AI_BUDGET_EXCEEDED",null);
            if(cause instanceof RemoteCallGuard.RetryableRemoteCallException r)
                return detail(r.status()==429?"MODEL_RATE_LIMITED":"MODEL_SERVICE_UNAVAILABLE",r.status());
            if(cause instanceof RemoteCallGuard.NonRetryableRemoteCallException r)
                return detail(r.status()==null?"MODEL_RESPONSE_INVALID":r.status()==401 || r.status()==403?"MODEL_ACCESS_DENIED"
                        :r.status()==402?"MODEL_QUOTA_EXHAUSTED":"MODEL_SERVICE_UNAVAILABLE",r.status());
            if(cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException || cause instanceof RemoteCallGuard.RemoteCallDeadlineException)
                return detail("MODEL_TIMEOUT",null);
            if(cause instanceof java.io.IOException)return detail("MODEL_CONNECTION_FAILED",null);
        }
        return detail("MODEL_SERVICE_UNAVAILABLE",null);
    }
}
