package com.example.mealcheck.dto;

import java.util.List;

public class KnowledgeDtos {
    public static class ReindexResponse {
        private int chunks;
        public ReindexResponse(int chunks) { this.chunks = chunks; }
        public int getChunks() { return chunks; }
    }
    public static class SearchResponse {
        private List<KnowledgeSnippet> results;
        public SearchResponse(List<KnowledgeSnippet> results) { this.results = results; }
        public List<KnowledgeSnippet> getResults() { return results; }
    }
}
