package com.example.mealcheck.controller;

import com.example.mealcheck.dto.assistant.*;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AssistantControllerTest {
    @Test void synchronousEndpointUsesManagedDeliveryAndReturnsItsRequestId() {
        var assistant=mock(LangChainDietAssistantService.class);var limits=mock(RateLimitService.class);
        var tasks=mock(AssistantTaskManager.class);
        var controller=new AssistantController(assistant,limits,mock(UserAccountRepository.class),mock(AssistantConversationService.class),
                mock(AssistantProactiveAdviceService.class),mock(UserGoalService.class),tasks);
        var account=new UserAccount();account.setId(7L);account.setUsername("test-user");var principal=new UserPrincipal(account);
        when(limits.consumeAssistant("test-user")).thenReturn(new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.ALLOWED,20,19,Duration.ZERO));
        var response=new AssistantResponse("建议",List.of());response.setRequestId("shared-request");
        when(assistant.deliver(eq(principal),eq("问题"),isNull(),isNull(),any())).thenReturn(response);
        when(tasks.<AssistantResponse>call(eq(7L),any())).thenAnswer(invocation->{
            Function<AssistantStreamDelivery,AssistantResponse> operation=invocation.getArgument(1);
            return operation.apply(new AssistantStreamDelivery(null));
        });
        var request=new AssistantRequest();request.setQuestion("问题");var result=controller.ask(principal,request);
        assertThat(result.getBody()).isSameAs(response);
        assertThat(result.getHeaders().getFirst("X-Assistant-Request-ID")).isEqualTo("shared-request");
        verify(tasks).call(eq(7L),any());verify(assistant,never()).ask(any(),anyString(),any(),any());
    }
}
