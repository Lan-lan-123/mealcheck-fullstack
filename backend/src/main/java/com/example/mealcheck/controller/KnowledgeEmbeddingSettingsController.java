package com.example.mealcheck.controller;

import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.KnowledgeEmbeddingConfigurationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/knowledge/embedding-settings")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeEmbeddingSettingsController {
    private final KnowledgeEmbeddingConfigurationService settings;
    public KnowledgeEmbeddingSettingsController(KnowledgeEmbeddingConfigurationService settings) { this.settings=settings; }
    @GetMapping public KnowledgeEmbeddingConfigurationService.View view() { return settings.view(); }
    @PutMapping public KnowledgeEmbeddingConfigurationService.View save(
            @Valid @RequestBody KnowledgeEmbeddingConfigurationService.Update request,
            @AuthenticationPrincipal UserPrincipal principal) { return settings.save(request,principal); }
    @PostMapping("/test") public KnowledgeEmbeddingConfigurationService.TestResult test(
            @Valid @RequestBody KnowledgeEmbeddingConfigurationService.Version request,
            @AuthenticationPrincipal UserPrincipal principal) { return settings.test(request,principal); }
    @PostMapping("/apply") public KnowledgeEmbeddingConfigurationService.ApplyResult apply(
            @Valid @RequestBody KnowledgeEmbeddingConfigurationService.Version request,
            @AuthenticationPrincipal UserPrincipal principal) { return settings.apply(request,principal); }
    @PostMapping("/preview") public java.util.Map<String,Object> preview(
            @Valid @RequestBody KnowledgeEmbeddingConfigurationService.Version request) { return settings.preview(request); }
}
