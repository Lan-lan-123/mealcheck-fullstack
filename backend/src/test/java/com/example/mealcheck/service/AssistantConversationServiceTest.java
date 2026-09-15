package com.example.mealcheck.service;

import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.AssistantConversationMessage;
import com.example.mealcheck.repository.AssistantConversationMessageRepository;
import com.example.mealcheck.repository.AssistantConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConversationServiceTest {
    @Test
    void loadsOnlyPendingSummaryBatchAndRecentWindow() {
        AssistantConversationRepository conversations = mock(AssistantConversationRepository.class);
        AssistantConversationMessageRepository messages = mock(AssistantConversationMessageRepository.class);
        AssistantConversationService service = new AssistantConversationService(conversations, messages);
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(7L);
        conversation.setSummarizedMessageCount(120);

        when(messages.countByConversation(conversation)).thenReturn(250L);
        when(messages.findWindow(7L, 120, 80)).thenReturn(List.of(message("old-1"), message("old-2")));
        when(messages.findByConversationOrderByCreatedAtDesc(eq(conversation), any(Pageable.class)))
                .thenReturn(List.of(message("recent-2"), message("recent-1")));

        AssistantConversationService.MemoryHistory window = service.memoryHistory(conversation, 50, 80);

        assertThat(window.summaryTargetCount()).isEqualTo(200);
        assertThat(window.summaryCandidates()).extracting(item -> item.getText())
                .containsExactly("old-1", "old-2");
        assertThat(window.recentMessages()).extracting(item -> item.getText())
                .containsExactly("recent-1", "recent-2");
        verify(messages).findWindow(7L, 120, 80);
    }

    private AssistantConversationMessage message(String text) {
        AssistantConversationMessage message = new AssistantConversationMessage();
        message.setRole("user");
        message.setText(text);
        return message;
    }
}
