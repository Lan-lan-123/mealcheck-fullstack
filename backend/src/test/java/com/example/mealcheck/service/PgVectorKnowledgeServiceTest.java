package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDateTime;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorKnowledgeServiceTest {

    @Test
    void usesWeightedRrfInsteadOfMixingRawRetrieverScores() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        AppProperties properties = new AppProperties();
        when(embeddingService.embed("减脂晚餐")).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingService.toPgVector(any(float[].class))).thenReturn("[0.1,0.2]");
        PgVectorKnowledgeService service = new PgVectorKnowledgeService(
                jdbcTemplate,
                embeddingService,
                properties,
                mock(KnowledgeVectorPersistenceService.class),
                mock(MarkdownKnowledgeChunker.class),
                new VectorCompressionService(properties)
        );

        service.search("减脂晚餐", 5);

        String sql = mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> "query".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.getArgument(0, String.class))
                .filter(value -> value.contains("WITH vector_candidates"))
                .findFirst()
                .orElseThrow();
        assertThat(sql).contains("ROW_NUMBER() OVER", "vector_rank", "title_rank", "content_rank");
        assertThat(sql).doesNotContain("1 - (k.embedding <=>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsEmbeddingWhenContentHashAndProviderSignatureAreUnchanged() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        KnowledgeVectorPersistenceService persistenceService = mock(KnowledgeVectorPersistenceService.class);
        MarkdownKnowledgeChunker chunker = mock(MarkdownKnowledgeChunker.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(7L);
        when(resultSet.getString("chunk_key")).thenReturn("stable-key");
        when(resultSet.getString("content_hash")).thenReturn("same-hash");
        when(resultSet.getString("embedding_signature")).thenReturn("semantic-v1:halfvec-v1");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("diet_guides.md")))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0)));
        when(embeddingService.signature()).thenReturn("semantic-v1");

        PgVectorKnowledgeService service = new PgVectorKnowledgeService(
                jdbcTemplate, embeddingService, new AppProperties(), persistenceService, chunker,
                new VectorCompressionService(new AppProperties()));
        PgVectorKnowledgeService.KnowledgeChunk chunk = new PgVectorKnowledgeService.KnowledgeChunk(
                "stable-key", "标题", "正文", 0, 2, "same-hash");

        PgVectorKnowledgeService.SyncResult result = service.syncSource(
                "diet_guides.md", "v1", LocalDateTime.now(), List.of(chunk));

        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        verify(embeddingService, never()).embed(anyString());
        verify(persistenceService).syncSource(eq("diet_guides.md"), any(), eq(List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void migratesExistingKnowledgeColumnToConfiguredDimension() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        KnowledgeVectorPersistenceService persistenceService = mock(KnowledgeVectorPersistenceService.class);
        AppProperties properties = new AppProperties();
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("vector(384)");
        when(embeddingService.signature()).thenReturn("hash-v1:512");
        when(embeddingService.providerName()).thenReturn("HashEmbeddingService / Development fallback");
        doReturn(List.of()).when(jdbcTemplate).query(
                eq("SELECT id, title, content FROM knowledge_chunks ORDER BY id"), any(RowMapper.class));

        PgVectorKnowledgeService service = new PgVectorKnowledgeService(
                jdbcTemplate, embeddingService, properties, persistenceService,
                mock(MarkdownKnowledgeChunker.class), new VectorCompressionService(properties));

        service.afterPropertiesSet();

        verify(persistenceService).migrateStorage(512, false, List.of(), "hash-v1:512:halfvec-v1");
    }

    @Test
    void convertsVectorToHalfvecWithoutRegeneratingSameDimensionEmbeddings() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        KnowledgeVectorPersistenceService persistenceService = mock(KnowledgeVectorPersistenceService.class);
        AppProperties properties = new AppProperties();
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class))).thenReturn("vector(512)");
        when(embeddingService.signature()).thenReturn("semantic-v1");

        PgVectorKnowledgeService service = new PgVectorKnowledgeService(
                jdbcTemplate, embeddingService, properties, persistenceService,
                mock(MarkdownKnowledgeChunker.class), new VectorCompressionService(properties));

        service.afterPropertiesSet();

        verify(persistenceService).migrateStorage(512, true, List.of(), "semantic-v1:halfvec-v1");
        verify(embeddingService, never()).embed(anyString());
    }
}
