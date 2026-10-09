package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.entity.UserAccount;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

public interface AssistantFunctionToolRuntime {
    List<ToolSpecification> specifications();

    ToolResult execute(UserAccount user, ToolExecutionRequest request, long deadlineNanos);

    record ToolResult(String content,
                      List<KnowledgeSnippet> references,
                      String status) {
        public ToolResult(String content, List<KnowledgeSnippet> references) {
            this(content, references, "SUCCESS");
        }

        public ToolResult {
            references = references == null ? List.of() : List.copyOf(references);
            status = status == null || status.isBlank() ? "SUCCESS" : status;
        }
    }
}
