package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.dto.assistant.AssistantConversationDetailResponse;
import com.example.mealcheck.dto.assistant.AssistantConversationSummary;
import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.AssistantConversationMessage;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.AssistantConversationMessageRepository;
import com.example.mealcheck.repository.AssistantConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

@Service
public class AssistantConversationService {
    private final AssistantConversationRepository conversationRepository;
    private final AssistantConversationMessageRepository messageRepository;

    public AssistantConversationService(AssistantConversationRepository conversationRepository,
                                        AssistantConversationMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public AssistantConversation resolve(UserAccount user, Long conversationId, String firstQuestion) {
        if (conversationId != null) {
            return conversationRepository.findByIdAndUser(conversationId, user).orElseThrow();
        }
        AssistantConversation conversation = new AssistantConversation();
        conversation.setUser(user);
        conversation.setTitle(title(firstQuestion));
        return conversationRepository.save(conversation);
    }

    @Transactional(readOnly = true)
    public List<AssistantConversationSummary> list(UserAccount user) {
        return conversationRepository.findTop20ByUserOrderByUpdatedAtDesc(user).stream()
                .map(conversation -> new AssistantConversationSummary(
                        conversation.getId(),
                        conversation.getTitle(),
                        conversation.getCreatedAt(),
                        conversation.getUpdatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AssistantConversationDetailResponse detail(UserAccount user, Long conversationId) {
        AssistantConversation conversation = conversationRepository.findByIdAndUser(conversationId, user).orElseThrow();
        return new AssistantConversationDetailResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getUpdatedAt(),
                history(conversation)
        );
    }

    @Transactional(readOnly = true)
    public List<AssistantChatMessage> history(AssistantConversation conversation) {
        return messageRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .map(message -> new AssistantChatMessage(message.getRole(), message.getText()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MemoryHistory memoryHistory(AssistantConversation conversation,
                                       int recentLimit,
                                       int summaryBatchLimit) {
        int safeRecentLimit = Math.max(2, Math.min(200, recentLimit));
        int safeSummaryBatchLimit = Math.max(1, Math.min(500, summaryBatchLimit));
        long totalCount = messageRepository.countByConversation(conversation);
        int summaryTargetCount = (int) Math.max(0L, totalCount - safeRecentLimit);
        int summarizedCount = Math.min(conversation.getSummarizedMessageCount(), summaryTargetCount);
        int pendingSummaryCount = Math.max(0, summaryTargetCount - summarizedCount);
        int summaryLimit = Math.min(safeSummaryBatchLimit, pendingSummaryCount);

        List<AssistantChatMessage> summaryCandidates = summaryLimit == 0 ? List.of()
                : toChatMessages(messageRepository.findWindow(
                        conversation.getId(), summarizedCount, summaryLimit));
        List<AssistantConversationMessage> recentEntities = new ArrayList<>(
                messageRepository.findByConversationOrderByCreatedAtDesc(
                        conversation, PageRequest.of(0, safeRecentLimit)));
        Collections.reverse(recentEntities);
        List<AssistantChatMessage> recentMessages = toChatMessages(recentEntities);

        List<AssistantChatMessage> contextMessages = new ArrayList<>(summaryCandidates);
        contextMessages.addAll(recentMessages);
        return new MemoryHistory(
                List.copyOf(contextMessages), summaryCandidates, recentMessages,
                summarizedCount, summaryTargetCount, totalCount);
    }

    @Transactional
    public void append(AssistantConversation conversation, String role, String text) {
        AssistantConversationMessage message = new AssistantConversationMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setText(text);
        messageRepository.save(message);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    @Transactional
    public void updateMemory(AssistantConversation conversation,
                             String summary,
                             String state,
                             int summarizedMessageCount) {
        conversation.setMemorySummary(summary);
        conversation.setMemoryState(state);
        conversation.setSummarizedMessageCount(summarizedMessageCount);
        conversationRepository.save(conversation);
    }

    @Transactional
    public void delete(UserAccount user, Long conversationId) {
        AssistantConversation conversation = conversationRepository.findByIdAndUser(conversationId, user).orElseThrow();
        messageRepository.deleteByConversation(conversation);
        conversationRepository.delete(conversation);
    }

    private String title(String question) {
        String normalized = question == null ? "新对话" : question.trim();
        if (normalized.isBlank()) {
            return "新对话";
        }
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 24) + "...";
    }

    private List<AssistantChatMessage> toChatMessages(List<AssistantConversationMessage> messages) {
        return messages.stream()
                .map(message -> new AssistantChatMessage(message.getRole(), message.getText()))
                .toList();
    }

    public record MemoryHistory(List<AssistantChatMessage> contextMessages,
                                List<AssistantChatMessage> summaryCandidates,
                                List<AssistantChatMessage> recentMessages,
                                int summarizedCount,
                                int summaryTargetCount,
                                long totalMessageCount) {
    }
}
