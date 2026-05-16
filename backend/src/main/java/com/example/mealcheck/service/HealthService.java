package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.HealthResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
public class HealthService {
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties properties;
    private final KnowledgeIndexService knowledgeIndexService;
    private final AiChatClient aiChatClient;
    private final AiStatusService aiStatusService;
    private final RedisCacheService redisCacheService;

    public HealthService(JdbcTemplate jdbcTemplate,
                         AppProperties properties,
                         KnowledgeIndexService knowledgeIndexService,
                         AiChatClient aiChatClient,
                         AiStatusService aiStatusService,
                         RedisCacheService redisCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.knowledgeIndexService = knowledgeIndexService;
        this.aiChatClient = aiChatClient;
        this.aiStatusService = aiStatusService;
        this.redisCacheService = redisCacheService;
    }

    public HealthResponse health() {
        boolean databaseReachable = databaseReachable();
        boolean redisReachable = redisCacheService.isReachable();
        boolean uploadWritable = uploadDirectoryWritable();
        long chunkCount = databaseReachable ? knowledgeIndexService.countChunks() : 0;
        String status = databaseReachable && redisReachable && uploadWritable ? "UP" : "DEGRADED";

        return new HealthResponse(
                status,
                databaseReachable,
                redisReachable,
                aiChatClient.isConfigured(),
                properties.getAi().getModel(),
                chunkCount,
                uploadWritable,
                aiStatusService.recentCalls(),
                LocalDateTime.now()
        );
    }

    private boolean databaseReachable() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean uploadDirectoryWritable() {
        try {
            Path uploadPath = Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);
            return Files.isWritable(uploadPath);
        } catch (Exception e) {
            return false;
        }
    }
}
