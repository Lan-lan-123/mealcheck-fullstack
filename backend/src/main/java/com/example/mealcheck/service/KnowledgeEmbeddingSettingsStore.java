package com.example.mealcheck.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEmbeddingSettingsStore {
    private final JdbcTemplate jdbc;

    public KnowledgeEmbeddingSettingsStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String mode() {
        return jdbc.queryForObject("SELECT mode FROM knowledge_embedding_settings WHERE id=1", String.class);
    }
}
