package com.example.mealcheck.service;

/** Only allowlisted summaries are persisted; provider bodies and credentials are excluded. */
public record KnowledgeJobFailure(String code, String summary, boolean retryable) {
    public static KnowledgeJobFailure classify(Throwable error) {
        for (Throwable cause=error; cause!=null; cause=cause.getCause()) {
            if (cause instanceof EmbeddingException e) return e.failure();
            if (cause instanceof RemoteCallGuard.RetryableRemoteCallException e) return http(e.status());
            if (cause instanceof RemoteCallGuard.NonRetryableRemoteCallException e && e.status()!=null) return http(e.status());
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof RemoteCallGuard.RemoteCallDeadlineException)
                return new KnowledgeJobFailure("EMBEDDING_TIMEOUT","向量接口调用超时，将自动重试；持续超时请检查接口和网络。",true);
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException)
                return new KnowledgeJobFailure("EMBEDDING_RESPONSE","向量接口返回格式无效，请检查模型服务后重试。",false);
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.io.IOException)
                return new KnowledgeJobFailure("EMBEDDING_NETWORK","无法连接向量接口，请检查网络和接口地址。",true);
            if (cause instanceof AiBudgetService.BudgetExceededException)
                return new KnowledgeJobFailure("BUDGET_EXCEEDED","模型调用预算已用尽，请在额度恢复后重试。",false);
            if (cause instanceof org.springframework.dao.DataAccessException)
                return new KnowledgeJobFailure("DATABASE_ERROR","向量保存或发布失败，请检查数据库后重试。",true);
            if (cause instanceof RemoteCallGuard.RemoteCallRejectedException)
                return new KnowledgeJobFailure("DEPENDENCY_BUSY","向量服务暂时繁忙，将自动重试。",true);
        }
        return new KnowledgeJobFailure("EMBEDDING_REBUILD_FAILED","向量任务执行失败，已保留原向量和暂存进度，可重试或查看服务日志。",true);
    }
    public static KnowledgeJobFailure http(int status) {
        if (status==401 || status==403) return new KnowledgeJobFailure("EMBEDDING_AUTH","向量接口鉴权失败，请检查 API Key、模型权限或账户额度后重试。",false);
        if (status==429) return new KnowledgeJobFailure("EMBEDDING_RATE_LIMIT","向量接口请求受限，将延迟重试；请检查频率或账户额度。",true);
        if (status>=500) return new KnowledgeJobFailure("EMBEDDING_PROVIDER","向量服务暂时异常，将自动重试。",true);
        return new KnowledgeJobFailure("EMBEDDING_REQUEST","向量接口拒绝请求，请检查模型名称、接口地址和维度配置。",false);
    }
    public static final class EmbeddingException extends IllegalStateException {
        private final KnowledgeJobFailure failure;
        public EmbeddingException(KnowledgeJobFailure failure) { super(failure.summary()); this.failure=failure; }
        public KnowledgeJobFailure failure() { return failure; }
    }
}
