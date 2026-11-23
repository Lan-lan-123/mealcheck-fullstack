package com.example.mealcheck.controller;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai-settings")
@PreAuthorize("hasRole('ADMIN')")
public class AiSettingsController {
    private final AiSettingsService settings;
    private final AiChatClient client;
    private final AppProperties properties;
    public AiSettingsController(AiSettingsService settings, AiChatClient client, AppProperties properties) {
        this.settings=settings; this.client=client; this.properties=properties;
    }
    @GetMapping public AiSettingsService.View view() { return settings.view(); }
    @PutMapping public AiSettingsService.View save(@Valid @RequestBody AiSettingsService.Update request,
            @AuthenticationPrincipal UserPrincipal principal) { return settings.save(request,principal); }
    @PostMapping("/reset") public AiSettingsService.View reset(@Valid @RequestBody AiSettingsService.Version request,
            @AuthenticationPrincipal UserPrincipal principal) { return settings.reset(request,principal); }
    public record TestResult(boolean success, String model, String message) { }
    @PostMapping("/test") public TestResult test(@Valid @RequestBody AiSettingsService.Version request,
            @AuthenticationPrincipal UserPrincipal principal) {
        var saved=settings.view();
        if (saved.version()!=request.version()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,"配置已被修改，请刷新后测试");
        var connection=properties.getAi().connection();
        try (var scope=AiCallContext.open(principal.getId(),Duration.ofSeconds(20))) {
            client.completeWith("ai-configuration-test","ai-configuration-test",connection.apiKey(),connection.baseUrl(),
                    connection.model(),20,List.of(Map.of("role","user","content","Reply with OK only.")),0.0);
            return new TestResult(true,connection.model(),"连接成功，模型可正常返回文本；识图能力请通过上传餐食图片验证。");
        } catch (AiBudgetService.BudgetExceededException | AiBudgetService.BudgetUnavailableException failure) { throw failure;
        } catch (RuntimeException failure) {
            return new TestResult(false,connection.model(),"连接测试失败，请检查模型权限、供应商额度、API Key 和 Base URL；详细状态请查看服务日志。");
        }
    }
}
