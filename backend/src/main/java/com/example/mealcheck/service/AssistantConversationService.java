package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

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
    private final com.example.mealcheck.repository.UserAccountRepository users;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantConversationMessageRepository messageRepository;

    public AssistantConversationService(AssistantConversationRepository conversationRepository,
                                        AssistantConversationMessageRepository messageRepository, com.example.mealcheck.repository.UserAccountRepository users) {
        this.users = users;
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
        return detail(user, conversationId, 40, null);
    }

    @Transactional(readOnly = true)
    public AssistantConversationDetailResponse detail(UserAccount user, Long conversationId, int limit, String before) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("每页消息数量必须为 1 到 100");
        AssistantConversation conversation = conversationRepository.findByIdAndUser(conversationId, user).orElseThrow();
        var pageable = PageRequest.of(0, limit + 1);
        List<AssistantConversationMessage> rows;
        if (before == null || before.isBlank()) rows = messageRepository.latestPage(conversation, pageable);
        else {
            var cursor = decodeCursor(before, conversationId);
            rows = messageRepository.beforePage(conversation, cursor.time(), cursor.id(), pageable);
        }
        boolean hasMore = rows.size() > limit;
        List<AssistantConversationMessage> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        String next = hasMore ? encodeCursor(conversationId, page.get(page.size() - 1)) : null;
        Collections.reverse(page);
        var response = new AssistantConversationDetailResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getUpdatedAt(),
                toChatMessages(page)
        );
        response.setPage(hasMore, next);
        return response;
    }

    private record Cursor(LocalDateTime time, long id) { }
    private Cursor decodeCursor(String value, long conversationId) {
        try {
            if (value.length() > 180) throw new IllegalArgumentException();
            String[] parts = new String(java.util.Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || Long.parseLong(parts[0]) != conversationId || Long.parseLong(parts[2]) <= 0)
                throw new IllegalArgumentException();
            return new Cursor(LocalDateTime.parse(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("会话分页游标无效"); }
    }
    private String encodeCursor(long conversationId, AssistantConversationMessage message) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (conversationId + "|" + message.getCreatedAt() + "|" + message.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
    public Long append(AssistantConversation conversation, String role, String text) {
        return append(conversation,role,text,null,"COMPLETE");
    }

    @Transactional
    public Long append(AssistantConversation conversation,String role,String text,String requestId,String status) {
        AssistantConversationMessage message = new AssistantConversationMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setText(text);
        message.setRequestId(requestId); message.setDeliveryStatus(status);
        messageRepository.save(message);
        conversation.setUpdatedAt(AppTime.now());
        conversationRepository.save(conversation);
        return message.getId();
    }

    @Transactional
    public Long appendTurn(AssistantConversation conversation,String question,String answer,String requestId,String status) {
        var owner=users.findByIdForUpdate(conversation.getUser().getId()).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        var current=conversationRepository.findByIdAndUser(conversation.getId(),owner).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"会话已删除"));
        append(current,"user",question,requestId,"COMPLETE");
        return append(current,"assistant",answer,requestId,status);
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
                .map(message -> { var dto=new AssistantChatMessage(message.getRole(),message.getText());
                    dto.setMessageId(message.getId()); dto.setRequestId(message.getRequestId()); dto.setDeliveryStatus(message.getDeliveryStatus()); return dto; })
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
