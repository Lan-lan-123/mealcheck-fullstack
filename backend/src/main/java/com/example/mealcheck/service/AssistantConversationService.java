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
import com.fasterxml.jackson.core.type.TypeReference;

@Service
public class AssistantConversationService {
    private final com.fasterxml.jackson.databind.ObjectMapper toolCallMapper = new com.fasterxml.jackson.databind.ObjectMapper();
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
            return owned(user, conversationId);
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
        AssistantConversation conversation = owned(user, conversationId);
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
        return appendWithTools(conversation, role, text, requestId, status, List.of());
    }

    private Long appendWithTools(AssistantConversation conversation, String role, String text, String requestId, String status,
                                  List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls) {
        return appendWithEvidence(conversation,role,text,requestId,status,toolCalls,List.of());
    }
    private Long appendWithEvidence(AssistantConversation conversation,String role,String text,String requestId,String status,
                                  List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls,
                                  List<com.example.mealcheck.dto.assistant.AssistantReference> references) {
        return appendWithEvidence(conversation,role,text,requestId,status,toolCalls,references,null);
    }
    private Long appendWithEvidence(AssistantConversation conversation,String role,String text,String requestId,String status,
                                  List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls,
                                  List<com.example.mealcheck.dto.assistant.AssistantReference> references,
                                  com.example.mealcheck.dto.assistant.AssistantResponse outcome) {
        AssistantConversationMessage message = new AssistantConversationMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setText(text);
        message.setRequestId(requestId); message.setDeliveryStatus(status);
        if(outcome!=null) {
            var saved=toolCallMapper.createObjectNode().put("answerStatus",outcome.getAnswerStatus());
            saved.put("reviewStatus",outcome.getReviewStatus());
            saved.set("answerReasonCodes",toolCallMapper.valueToTree(outcome.getAnswerReasonCodes()));
            if(outcome.getFailureCode()!=null)saved.put("failureCode",outcome.getFailureCode());
            if(outcome.getUpstreamStatus()!=null)saved.put("upstreamStatus",outcome.getUpstreamStatus());
            message.setOutcomeJson(saved.toString());
        }
        if (!toolCalls.isEmpty()) {
            try { message.setToolCallsJson(toolCallMapper.writeValueAsString(toolCalls)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Cannot save tool execution evidence", error); }
        }
        if (!references.isEmpty() && "assistant".equals(role) && "COMPLETE".equals(status)) {
            try { message.setReferencesJson(toolCallMapper.writeValueAsString(references)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Cannot save answer references",error); }
        }
        messageRepository.save(message);
        conversation.setUpdatedAt(AppTime.now());
        conversationRepository.save(conversation);
        return message.getId();
    }

    @Transactional
    public Long appendTurn(AssistantConversation conversation,String question,String answer,String requestId,String status) {
        return appendTurn(conversation, question, answer, requestId, status, List.of());
    }

    @Transactional
    public Long appendTurn(AssistantConversation conversation,String question,String answer,String requestId,String status,
                           List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls) {
        return appendTurn(conversation,question,answer,requestId,status,toolCalls,List.of());
    }
    @Transactional
    public Long appendTurn(AssistantConversation conversation,String question,String answer,String requestId,String status,
                           List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls,
                           List<com.example.mealcheck.dto.assistant.AssistantReference> references) {
        return appendTurn(conversation,question,answer,requestId,status,toolCalls,references,null);
    }
    @Transactional
    public Long appendTurn(AssistantConversation conversation,String question,String answer,String requestId,String status,
                           List<com.example.mealcheck.dto.assistant.AssistantToolCall> toolCalls,
                           List<com.example.mealcheck.dto.assistant.AssistantReference> references,
                           com.example.mealcheck.dto.assistant.AssistantResponse outcome) {
        AssistantExecutionScope.preparePublication();
        var owner=users.findByIdForUpdate(conversation.getUser().getId()).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        var current=conversationRepository.findByIdAndUser(conversation.getId(),owner).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"会话已删除"));
        AssistantExecutionScope.guardPublication("COMPLETE".equals(status));
        if("COMPLETE".equals(status))AiCallContext.guardTransactionCommit();
        append(current,"user",question,requestId,"COMPLETE");
        return appendWithEvidence(current,"assistant",answer,requestId,status,toolCalls,references,outcome);
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
        AssistantConversation conversation = owned(user, conversationId);
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

    private AssistantConversation owned(UserAccount user, Long conversationId) {
        return conversationRepository.findByIdAndUser(conversationId, user)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在"));
    }

    private List<AssistantChatMessage> toChatMessages(List<AssistantConversationMessage> messages) {
        return messages.stream()
                .map(message -> { var dto=new AssistantChatMessage(message.getRole(),message.getText());
                    dto.setMessageId(message.getId()); dto.setRequestId(message.getRequestId()); dto.setDeliveryStatus(message.getDeliveryStatus());
                    if("assistant".equals(message.getRole())) {
                        var outcome=new com.example.mealcheck.dto.assistant.AssistantResponse(message.getText(),List.of());
                        outcome.setReviewStatus("UNKNOWN");
                        if("ERROR".equals(message.getDeliveryStatus()))outcome.degraded("MODEL_SERVICE_UNAVAILABLE",null);
                        if(message.getOutcomeJson()!=null) {
                            try {
                                var saved=toolCallMapper.readTree(message.getOutcomeJson());
                                outcome.setReviewStatus(saved.path("reviewStatus").asText("UNKNOWN"));
                                String state=saved.path("answerStatus").asText();
                                if("PARTIAL".equals(state))outcome.partial(toolCallMapper.convertValue(saved.path("answerReasonCodes"),new TypeReference<List<String>>() {}));
                                else if("DEGRADED".equals(state))outcome.degraded(saved.path("failureCode").asText("MODEL_SERVICE_UNAVAILABLE"),saved.path("upstreamStatus").isInt()?saved.path("upstreamStatus").asInt():null);
                                else if(!"COMPLETE".equals(state))outcome.partial(List.of("INVALID_SAVED_OUTCOME"));
                            } catch(Exception malformed) {outcome.partial(List.of("INVALID_SAVED_OUTCOME"));}
                        }
                        dto.setOutcome(outcome);
                    }
                    if ("assistant".equals(message.getRole()) && "COMPLETE".equals(message.getDeliveryStatus()) && message.getReferencesJson()!=null) {
                        try { dto.setReferences(toolCallMapper.readValue(message.getReferencesJson(),new TypeReference<List<com.example.mealcheck.dto.assistant.AssistantReference>>() {})); }
                        catch (Exception malformed) { dto.setReferences(List.of()); }
                    }
                    if (message.getToolCallsJson() != null) {
                        try { dto.setToolCalls(toolCallMapper.readValue(message.getToolCallsJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<com.example.mealcheck.dto.assistant.AssistantToolCall>>() {})); }
                        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Cannot read tool execution evidence", error); }
                    }
                    return dto; })
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
