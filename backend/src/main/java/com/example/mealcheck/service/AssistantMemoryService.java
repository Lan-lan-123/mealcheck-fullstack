package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.entity.AssistantConversation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AssistantMemoryService {
    private static final List<String> FOLLOW_UP_MARKERS = List.of(
            "这个", "那个", "这种", "那种", "它", "这样", "那样", "呢", "还要", "继续", "刚才",
            "what about", "how about", "it", "that", "this"
    );

    private final AppProperties properties;
    private final AssistantConversationService conversationService;
    private final ApplicationObservability observability;

    public AssistantMemoryService(AppProperties properties,
                                  AssistantConversationService conversationService,
                                  ApplicationObservability observability) {
        this.properties = properties;
        this.conversationService = conversationService;
        this.observability = observability;
    }

    public MemoryContext prepare(AssistantConversation conversation,
                                 String question,
                                 String intentCode,
                                 AssistantConversationService.MemoryHistory memoryHistory,
                                 List<AssistantChatMessage> mergedHistory) {
        List<AssistantChatMessage> summaryCandidates = validMessages(memoryHistory.summaryCandidates());
        List<AssistantChatMessage> deduplicated = deduplicate(mergedHistory);
        int summarizedCount = Math.min(memoryHistory.summarizedCount(), memoryHistory.summaryTargetCount());
        String summary = nullToEmpty(conversation.getMemorySummary());
        boolean summaryUpdated = !summaryCandidates.isEmpty();
        if (summaryUpdated) {
            summary = appendSummary(summary, summaryCandidates);
            summarizedCount = Math.min(
                    memoryHistory.summaryTargetCount(), summarizedCount + summaryCandidates.size());
        }

        String activeTopic = activeTopic(question, deduplicated);
        String state = "intent=" + safeIntent(intentCode) + "; activeTopic=" + activeTopic;
        String standaloneQuestion = rewriteFollowUp(question, activeTopic, summary);
        List<AssistantChatMessage> working = selectWorkingMessages(standaloneQuestion, deduplicated);
        int estimatedTokens = estimateTokens(working);

        if (!Objects.equals(summary, nullToEmpty(conversation.getMemorySummary()))
                || !Objects.equals(state, nullToEmpty(conversation.getMemoryState()))
                || summarizedCount != conversation.getSummarizedMessageCount()) {
            conversationService.updateMemory(conversation, summary, state, summarizedCount);
        }
        observability.recordAssistantMemory(
                working.size(), estimatedTokens, summaryUpdated,
                !standaloneQuestion.equals(question == null ? "" : question.trim()));
        return new MemoryContext(working, summary, state, standaloneQuestion, estimatedTokens);
    }

    public MemoryContext prepare(AssistantConversation conversation,
                                 String question,
                                 String intentCode,
                                 List<AssistantChatMessage> persistedHistory,
                                 List<AssistantChatMessage> mergedHistory) {
        List<AssistantChatMessage> persisted = validMessages(persistedHistory);
        int summaryCutoff = Math.max(0, persisted.size() - safeRecentMessages());
        int summarizedCount = Math.min(conversation.getSummarizedMessageCount(), summaryCutoff);
        AssistantConversationService.MemoryHistory memoryHistory = new AssistantConversationService.MemoryHistory(
                persisted,
                persisted.subList(summarizedCount, summaryCutoff),
                persisted.subList(summaryCutoff, persisted.size()),
                summarizedCount,
                summaryCutoff,
                persisted.size());
        return prepare(conversation, question, intentCode, memoryHistory, mergedHistory);
    }

    public List<AssistantChatMessage> selectWorkingMessages(String question,
                                                             List<AssistantChatMessage> messages) {
        List<AssistantChatMessage> valid = deduplicate(messages);
        if (valid.isEmpty()) {
            return List.of();
        }

        int recentCount = Math.min(safeRecentMessages(), valid.size());
        int recentStart = valid.size() - recentCount;
        Set<Integer> selected = new LinkedHashSet<>();
        for (int index = recentStart; index < valid.size(); index++) {
            selected.add(index);
        }

        int relevantLimit = Math.max(0, Math.min(20,
                properties.getAssistantMemory().getRelevantHistoryMessages()));
        List<ScoredMessage> relevant = new ArrayList<>();
        for (int index = 0; index < recentStart; index++) {
            double score = relevance(question, valid.get(index).getText());
            if (score > 0.0) {
                relevant.add(new ScoredMessage(index, score));
            }
        }
        relevant.stream()
                .sorted(Comparator.comparingDouble(ScoredMessage::score).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredMessage::index).reversed()))
                .limit(relevantLimit)
                .forEach(item -> selected.add(item.index()));

        List<Integer> orderedIndexes = selected.stream().sorted().toList();
        List<AssistantChatMessage> selectedMessages = orderedIndexes.stream().map(valid::get).toList();
        return applyTokenBudget(selectedMessages, safeHistoryTokenBudget());
    }

    int estimateTokens(List<AssistantChatMessage> messages) {
        return messages == null ? 0 : messages.stream()
                .mapToInt(message -> estimateTokens(message == null ? "" : message.getText()) + 2)
                .sum();
    }

    int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                tokens++;
            } else if (Character.isWhitespace(codePoint)) {
                if (asciiRun > 0) {
                    tokens += Math.max(1, (asciiRun + 3) / 4);
                    asciiRun = 0;
                }
            } else {
                asciiRun++;
            }
        }
        if (asciiRun > 0) {
            tokens += Math.max(1, (asciiRun + 3) / 4);
        }
        return Math.max(1, tokens);
    }

    private List<AssistantChatMessage> applyTokenBudget(List<AssistantChatMessage> messages, int budget) {
        List<AssistantChatMessage> reversed = new ArrayList<>();
        int remaining = budget;
        for (int index = messages.size() - 1; index >= 0 && remaining > 0; index--) {
            AssistantChatMessage message = messages.get(index);
            int tokens = estimateTokens(message.getText()) + 2;
            if (tokens <= remaining) {
                reversed.add(copy(message));
                remaining -= tokens;
            } else if (reversed.isEmpty()) {
                reversed.add(new AssistantChatMessage(message.getRole(), truncateToTokens(message.getText(), remaining)));
                remaining = 0;
            }
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private String appendSummary(String existing, List<AssistantChatMessage> messages) {
        StringBuilder builder = new StringBuilder(existing == null ? "" : existing.trim());
        for (AssistantChatMessage message : messages) {
            String text = compact(message.getText(), 180);
            if (text.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(normalizeRole(message.getRole())).append(": ").append(text);
        }
        return keepLatestTokens(builder.toString(), safeSummaryTokenBudget());
    }

    private String activeTopic(String question, List<AssistantChatMessage> history) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if ((!isFollowUp(normalizedQuestion) || hasSpecificTopic(normalizedQuestion))
                && normalizedQuestion.length() >= 2) {
            return compact(normalizedQuestion, 240);
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            AssistantChatMessage message = history.get(index);
            if ("user".equals(normalizeRole(message.getRole()))
                    && message.getText() != null && !message.getText().isBlank()
                    && !isFollowUp(message.getText())) {
                return compact(message.getText(), 240);
            }
        }
        return compact(normalizedQuestion, 240);
    }

    private String rewriteFollowUp(String question, String activeTopic, String summary) {
        String safeQuestion = question == null ? "" : question.trim();
        if (!isFollowUp(safeQuestion) || activeTopic.isBlank() || activeTopic.equals(safeQuestion)) {
            return safeQuestion;
        }
        StringBuilder rewritten = new StringBuilder(safeQuestion)
                .append("；当前对话主题：").append(activeTopic);
        if (!summary.isBlank()) {
            rewritten.append("；历史摘要：").append(compact(summary, 320));
        }
        return rewritten.toString();
    }

    private boolean isFollowUp(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 6
                || FOLLOW_UP_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean hasSpecificTopic(String question) {
        String remaining = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (List.of("这个", "那个", "这种", "那种", "它", "this", "that", "it").stream()
                .anyMatch(remaining::contains)) {
            return false;
        }
        for (String marker : FOLLOW_UP_MARKERS) {
            remaining = remaining.replace(marker, "");
        }
        remaining = remaining.replaceAll("[^\\p{IsHan}a-z0-9]+", "");
        return remaining.length() >= 2;
    }

    private double relevance(String question, String text) {
        Set<String> queryTerms = terms(question);
        Set<String> messageTerms = terms(text);
        if (queryTerms.isEmpty() || messageTerms.isEmpty()) {
            return 0.0;
        }
        long intersection = queryTerms.stream().filter(messageTerms::contains).count();
        return intersection == 0 ? 0.0 : (double) intersection / Math.sqrt(queryTerms.size() * messageTerms.size());
    }

    private Set<String> terms(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ").trim();
        Set<String> terms = new HashSet<>();
        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            if (word.codePoints().anyMatch(this::isCjk) && word.length() > 1) {
                for (int index = 0; index < word.length() - 1; index++) {
                    terms.add(word.substring(index, index + 2));
                }
            } else if (word.length() > 1) {
                terms.add(word);
            }
        }
        return terms;
    }

    private List<AssistantChatMessage> deduplicate(List<AssistantChatMessage> messages) {
        Map<String, AssistantChatMessage> unique = new LinkedHashMap<>();
        for (AssistantChatMessage message : validMessages(messages)) {
            String role = normalizeRole(message.getRole());
            String text = message.getText().trim();
            unique.putIfAbsent(role + "\u0000" + text, new AssistantChatMessage(role, text));
        }
        return List.copyOf(unique.values());
    }

    private List<AssistantChatMessage> validMessages(List<AssistantChatMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> message.getText() != null && !message.getText().isBlank())
                .toList();
    }

    private AssistantChatMessage copy(AssistantChatMessage message) {
        return new AssistantChatMessage(normalizeRole(message.getRole()), message.getText().trim());
    }

    private String normalizeRole(String role) {
        return role != null && "assistant".equals(role.toLowerCase(Locale.ROOT)) ? "assistant" : "user";
    }

    private String truncateToTokens(String text, int tokenLimit) {
        if (text == null || tokenLimit <= 0) {
            return "";
        }
        int end = 0;
        while (end < text.length() && estimateTokens(text.substring(0, end + 1)) <= tokenLimit) {
            end++;
        }
        return text.substring(0, Math.max(0, end)).trim();
    }

    private String keepLatestTokens(String text, int tokenLimit) {
        if (estimateTokens(text) <= tokenLimit) {
            return text;
        }
        int start = 0;
        while (start < text.length() && estimateTokens(text.substring(start)) > tokenLimit) {
            start++;
        }
        return text.substring(Math.min(start, text.length())).trim();
    }

    private String compact(String text, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }

    private String safeIntent(String intentCode) {
        return intentCode == null || intentCode.isBlank() ? "GENERAL" : intentCode.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int safeRecentMessages() {
        return Math.max(2, Math.min(20, properties.getAssistantMemory().getRecentMessages()));
    }

    private int safeHistoryTokenBudget() {
        return Math.max(200, Math.min(8000, properties.getAssistantMemory().getHistoryTokenBudget()));
    }

    private int safeSummaryTokenBudget() {
        return Math.max(100, Math.min(4000, properties.getAssistantMemory().getSummaryMaxTokens()));
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private record ScoredMessage(int index, double score) {}

    public record MemoryContext(List<AssistantChatMessage> workingMessages,
                                String summary,
                                String state,
                                String standaloneQuestion,
                                int estimatedHistoryTokens) {}
}
