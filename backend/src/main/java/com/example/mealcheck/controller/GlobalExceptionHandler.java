package com.example.mealcheck.controller;

import com.example.mealcheck.dto.ApiErrorResponse;
import com.example.mealcheck.service.AiChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> badCredentials(BadCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_BAD_CREDENTIALS", safeMessage(ex, "用户名或密码错误。"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", safeMessage(ex, "请求参数不正确。"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("请求参数校验失败。");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        return error(
                status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                "REQUEST_FAILED",
                ex.getReason() == null ? "请求处理失败。" : ex.getReason()
        );
    }

    @ExceptionHandler(AiChatClient.AiClientException.class)
    public ResponseEntity<ApiErrorResponse> aiClient(AiChatClient.AiClientException ex) {
        log.warn("AI client error: {}", ex.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "AI_CALL_FAILED", safeMessage(ex, "AI 服务调用失败。"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> illegalState(IllegalStateException ex) {
        log.warn("Service dependency unavailable: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", safeMessage(ex, "服务依赖暂时不可用，请稍后重试。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> server(Exception ex) {
        log.error("Unhandled server error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误，请稍后重试。");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        ApiErrorResponse body = new ApiErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                LocalDateTime.now()
        );
        return ResponseEntity.status(status).body(body);
    }

    private String safeMessage(Exception ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }
}
