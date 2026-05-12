package com.example.mealcheck.controller;

import com.example.mealcheck.dto.KnowledgeDtos;
import com.example.mealcheck.service.KnowledgeIndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeIndexService knowledgeIndexService;

    public KnowledgeController(KnowledgeIndexService knowledgeIndexService) {
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @PostMapping("/reindex")
    public KnowledgeDtos.ReindexResponse reindex() {
        return new KnowledgeDtos.ReindexResponse(knowledgeIndexService.reindex());
    }

    @GetMapping("/search")
    public KnowledgeDtos.SearchResponse search(@RequestParam String q,
                                               @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return new KnowledgeDtos.SearchResponse(knowledgeIndexService.search(q, Math.max(1, Math.min(limit, 10))));
    }
}
