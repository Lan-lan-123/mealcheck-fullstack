package com.example.mealcheck.controller;

import com.example.mealcheck.dto.assistant.AssistantRequest;
import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.example.mealcheck.dto.assistant.AssistantConversationDetailResponse;
import com.example.mealcheck.dto.assistant.AssistantConversationSummary;
import com.example.mealcheck.dto.assistant.AssistantProactiveAdviceResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.AssistantConversationService;
import com.example.mealcheck.service.AssistantProactiveAdviceService;
import com.example.mealcheck.service.LangChainDietAssistantService;
import com.example.mealcheck.service.RateLimitService;
import com.example.mealcheck.service.UserGoalService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final UserAccountRepository userAccountRepository;
    private final AssistantConversationService conversationService;
    private final AssistantProactiveAdviceService proactiveAdviceService;
    private final UserGoalService userGoalService;

    public AssistantController(LangChainDietAssistantService assistantService,
                               RateLimitService rateLimitService,
                               UserAccountRepository userAccountRepository,
                               AssistantConversationService conversationService,
                               AssistantProactiveAdviceService proactiveAdviceService,
                               UserGoalService userGoalService) {
        this.assistantService = assistantService;
        this.rateLimitService = rateLimitService;
        this.userAccountRepository = userAccountRepository;
        this.conversationService = conversationService;
        this.proactiveAdviceService = proactiveAdviceService;
        this.userGoalService = userGoalService;
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
        return assistantService.ask(principal, question, request.getConversationId(), request.getHistory());
    }

    @GetMapping("/assistant/conversations")
    public java.util.List<AssistantConversationSummary> conversations(@AuthenticationPrincipal UserPrincipal principal) {
        return conversationService.list(user(principal));
    }

    @GetMapping("/assistant/conversations/{id}")
    public AssistantConversationDetailResponse conversation(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable Long id) {
        return conversationService.detail(user(principal), id);
    }

    @DeleteMapping("/assistant/conversations/{id}")
    public void deleteConversation(@AuthenticationPrincipal UserPrincipal principal,
                                   @PathVariable Long id) {
        conversationService.delete(user(principal), id);
    }

    @GetMapping("/assistant/proactive")
    public AssistantProactiveAdviceResponse proactive(@AuthenticationPrincipal UserPrincipal principal) {
        UserAccount user = user(principal);
        return proactiveAdviceService.build(user, userGoalService.effectiveGoal(user, "current"));
    }

    private UserAccount user(UserPrincipal principal) {
        return userAccountRepository.findByUsername(principal.getUsername()).orElseThrow();
    }
}
