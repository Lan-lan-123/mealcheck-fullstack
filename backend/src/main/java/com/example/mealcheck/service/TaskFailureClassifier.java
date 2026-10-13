package com.example.mealcheck.service;

import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.RejectedExecutionException;

/** Allowlisted summaries: never persist provider payloads, credentials or exception messages. */
public final class TaskFailureClassifier {
    private TaskFailureClassifier() { }
    public record Failure(String code, String summary, boolean recoverable) { }
    public static Failure classify(Throwable error, String phase) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UploadBusyException) return new Failure("UPLOAD_BUSY", "上传入口繁忙，可使用原图和原请求标识稍后重试", true);
            if (cause instanceof AiBudgetService.BudgetExceededException) return new Failure("AI_BUDGET_EXCEEDED", "今日 AI 预算已用尽，请在额度重置后重试", true);
            if (cause instanceof AiBudgetService.BudgetUnavailableException) return new Failure("AI_BUDGET_UNAVAILABLE", "AI 预算服务暂时不可用", true);
            if (cause instanceof RemoteCallGuard.RemoteCallDeadlineException) return new Failure("REMOTE_TIMEOUT", "AI 操作已超过总超时限制", true);
            if (cause instanceof DataAccessException) return new Failure("DATABASE_UNAVAILABLE", "数据库访问或结果保存失败，请排障后重试", true);
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException)
                return new Failure("REMOTE_TIMEOUT", "远程调用超时，请确认服务恢复后重试", true);
            if (cause instanceof RejectedExecutionException || cause instanceof RemoteCallGuard.RemoteCallRejectedException)
                return new Failure("DEPENDENCY_BUSY", "执行器或远程服务暂时繁忙", true);
            if (cause instanceof ResponseStatusException status) {
                int code = status.getStatusCode().value();
                if (code == 429 || code >= 500) return new Failure("DEPENDENCY_UNAVAILABLE", "依赖服务暂时不可用或请求受限", true);
                return new Failure("BUSINESS_VALIDATION", "输入或业务条件不满足，请检查后更换输入", false);
            }
            if (cause instanceof IllegalArgumentException) return new Failure("INVALID_INPUT", "输入或消息不符合要求", false);
            if (cause instanceof RemoteCallGuard.NonRetryableRemoteCallException) return new Failure("REMOTE_CONFIGURATION", "远程调用被拒绝，请检查密钥、模型配置及输入后重试", true);
        }
        return new Failure("SAVE".equals(phase) ? "RESULT_SAVE_FAILED" : "EXECUTION_FAILED",
                "SAVE".equals(phase) ? "结果保存失败，请排障后重试" : "任务执行失败，请结合服务日志排查", true);
    }
}
