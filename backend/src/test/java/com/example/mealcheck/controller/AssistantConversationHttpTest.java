package com.example.mealcheck.controller;

import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.AssistantConversationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AssistantConversationHttpTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AssistantConversationRepository conversations = mock(AssistantConversationRepository.class);
    private final AssistantConversationMessageRepository messages = mock(AssistantConversationMessageRepository.class);
    private final AssistantConversationService service = new AssistantConversationService(conversations, messages, users);
    private final UserAccount owner = new UserAccount();
    private MockMvc http;

    @BeforeEach void setup() {
        owner.setId(7L); owner.setUsername("owner"); owner.setRole("USER");
        when(users.findById(7L)).thenReturn(Optional.of(owner));
        var other = new UserAccount(); other.setId(8L);
        var foreign = new AssistantConversation(); foreign.setId(99L); foreign.setUser(other);
        when(conversations.findByIdAndUser(99L, other)).thenReturn(Optional.of(foreign));
        var principal = new UserPrincipal(owner);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        http = MockMvcBuilders.standaloneSetup(new AssistantController(null, null, users, service, null, null, null))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(longs = {404L, 99L})
    void absentAndForeignConversationsHaveTheSame404ForReadAndDelete(long id) throws Exception {
        http.perform(get("/api/assistant/conversations/{id}", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("会话不存在"));
        http.perform(delete("/api/assistant/conversations/{id}", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("会话不存在"));
        verify(conversations, times(2)).findByIdAndUser(id, owner);
        verify(conversations, never()).delete(any());
        verifyNoInteractions(messages);
    }

    @Test void deletedConversationStopsResolvingBeforeCreatingAnyNewConversation() {
        assertThatThrownBy(() -> service.resolve(owner, 404L, "question"))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(conversations, never()).save(any());
        verifyNoInteractions(messages);
    }

    @Test void ownedConversationCanBeReadAndDeletedAndThenReturns404() throws Exception {
        var conversation = new AssistantConversation(); conversation.setId(10L); conversation.setUser(owner); conversation.setTitle("Owned");
        when(conversations.findByIdAndUser(10L, owner)).thenReturn(Optional.of(conversation), Optional.of(conversation), Optional.empty());
        http.perform(get("/api/assistant/conversations/10")).andExpect(status().isOk());
        http.perform(delete("/api/assistant/conversations/10")).andExpect(status().isOk());
        verify(messages).deleteByConversation(conversation);
        verify(conversations).delete(conversation);
        http.perform(get("/api/assistant/conversations/10")).andExpect(status().isNotFound());
    }
}
