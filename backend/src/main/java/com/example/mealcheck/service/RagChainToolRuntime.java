package com.example.mealcheck.service;

import com.example.mealcheck.dto.RagChainReport;
import com.example.mealcheck.entity.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real retrieval with production tool definitions, and no personal reads or usage-stat writes. */
final class RagChainToolRuntime implements AssistantFunctionToolRuntime {
    private final AssistantFunctionToolRegistry registry;
    private final KnowledgeIndexService knowledge;
    private final ObjectMapper mapper;
    private final List<RagChainReport.Retrieval> retrievals = new ArrayList<>();

    RagChainToolRuntime(AssistantFunctionToolRegistry registry, KnowledgeIndexService knowledge, ObjectMapper mapper) {
        this.registry = registry; this.knowledge = knowledge; this.mapper = mapper;
    }
    @Override public List<ToolSpecification> specifications() { return registry.specifications(); }
    List<RagChainReport.Retrieval> retrievals() { return List.copyOf(retrievals); }

    @Override public ToolResult execute(UserAccount user, ToolExecutionRequest request, long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) throw new IllegalStateException("RAG evaluation deadline exceeded");
        if (request == null || !RagToolEvaluationRules.SEARCH.equals(request.name()))
            throw new IllegalArgumentException("RAG chain evaluation only allows knowledge search");
        String query;
        int limit;
        try {
            var args = mapper.readTree(request.arguments() == null ? "{}" : request.arguments());
            if (args == null || !args.isObject()) throw new IllegalArgumentException("Invalid tool arguments");
            var names = args.fieldNames();
            while (names.hasNext()) if (!Set.of("query", "limit").contains(names.next()))
                throw new IllegalArgumentException("Unknown tool argument");
            var text = args.path("query");
            if (!text.isTextual() || text.asText().isBlank() || text.asText().length() > 500)
                throw new IllegalArgumentException("Invalid query");
            query = text.asText().trim();
            var count = args.get("limit");
            if (count != null && !count.isNull() && (!count.isIntegralNumber() || !count.canConvertToInt()))
                throw new IllegalArgumentException("Invalid limit");
            limit = count == null || count.isNull() ? 3 : count.intValue();
            if (limit < 1 || limit > 5) throw new IllegalArgumentException("Invalid limit");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid tool JSON", invalid); }
        long startedAt = System.nanoTime();
        try {
            var snippets = knowledge.searchForEvaluation(query, limit);
            if (System.nanoTime() >= deadlineNanos) throw new IllegalStateException("RAG evaluation deadline exceeded");
            AiCallContext.checkExecution();
            var hits = snippets.stream().map(item -> new RagChainReport.Hit(
                    item.getId(), item.getTitle(), item.getCategory(), item.getScore(),
                    RagEvidenceLabels.contentId(item.getTitle(), item.getContent()), item.getContent())).toList();
            var views = snippets.stream().map(item -> Map.of("id", item.getId(), "title", item.getTitle(),
                    "content", item.getContent(), "score", item.getScore())).toList();
            String content = mapper.writeValueAsString(Map.of("count", views.size(), "results", views));
            retrievals.add(new RagChainReport.Retrieval(request.id(), query, limit, "SUCCESS", elapsed(startedAt), hits, null));
            return new ToolResult(content, snippets);
        } catch (Exception error) {
            retrievals.add(new RagChainReport.Retrieval(request.id(), query, limit, "ERROR", elapsed(startedAt),
                    List.of(), error.getClass().getSimpleName()));
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("RAG search result serialization failed", error);
        }
    }
    private long elapsed(long startedAt) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }
}
