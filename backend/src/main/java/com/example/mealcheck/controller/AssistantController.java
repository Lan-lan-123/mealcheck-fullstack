package com.example.mealcheck.controller;

import com.example.mealcheck.dto.assistant.AssistantRequest;
import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.LangChainDietAssistantService;
import com.example.mealcheck.service.RateLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private final LangChainDietAssistantService assistantService;
    private final RateLimitService rateLimitService;

    public AssistantController(LangChainDietAssistantService assistantService,
                               RateLimitService rateLimitService) {
        this.assistantService = assistantService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping({"/assistant/ask", "/chat"})
    public AssistantResponse ask(@AuthenticationPrincipal UserPrincipal principal,
                                 @RequestBody AssistantRequest request) {
        String question = request.questionText();
        if (question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty.");
        }
        if (!rateLimitService.allow("assistant:" + principal.getUsername(), 20, Duration.ofMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "提问太频繁了，请稍后再试。");
        }
        return assistantService.ask(principal, question, request.getHistory());
    }
}
