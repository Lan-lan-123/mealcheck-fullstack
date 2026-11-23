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
    @Test void blankQueriesNeverReachDatabaseOrEmbeddingProvider() {
        var jdbc=mock(JdbcTemplate.class); var embedding=mock(KnowledgeEmbeddingService.class);
        var properties=new AppProperties();
        var service=new PgVectorKnowledgeService(jdbc,embedding,properties,mock(KnowledgeVectorPersistenceService.class),
                new MarkdownKnowledgeChunker(properties),new VectorCompressionService(properties),mock(KnowledgeSourceSyncStore.class));
        for (String query : new String[]{null,""," \n\t"}) assertThat(service.search(query,5)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc,embedding);
    }

    @Test @SuppressWarnings("unchecked")
    void modelSwitchDuringQueryRetriesWithTheNewVectorSpace() {
        var jdbc=mock(JdbcTemplate.class); var embedding=mock(KnowledgeEmbeddingService.class);
        var properties=new AppProperties();
        var signature=new java.util.concurrent.atomic.AtomicReference<>("old:512");
        when(embedding.signature()).thenAnswer(call -> signature.get());
        when(embedding.activeDimension()).thenAnswer(call -> signature.get().startsWith("old")?512:1024);
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenAnswer(call -> "halfvec("+(signature.get().startsWith("old")?512:1024)+")");
        when(embedding.embedForSignature(eq("午餐"),anyString())).thenAnswer(call -> {
            String requested=call.getArgument(1);
            signature.set("new:1024");
            return new float[requested.startsWith("old")?512:1024];
        });
        when(embedding.toPgVector(any())).thenReturn("[1,0]");
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("different vector dimensions"))
                .thenReturn(List.of());
        var service=new PgVectorKnowledgeService(jdbc,embedding,properties,mock(KnowledgeVectorPersistenceService.class),
                new MarkdownKnowledgeChunker(properties),new VectorCompressionService(properties),mock(KnowledgeSourceSyncStore.class));
        assertThat(service.search("午餐",5)).isEmpty();
        verify(embedding).embedForSignature("午餐","old:512:halfvec-v1");
        verify(embedding).embedForSignature("午餐","new:1024:halfvec-v1");
        assertThat(service.retrievalSignature()).contains("new:1024:halfvec-v1");
    }
    @Test void administratorWritesSaveUsageWithTheVectorFromOneCall() {
        var jdbc = mock(JdbcTemplate.class);
        var embedding = mock(KnowledgeEmbeddingService.class);
        var persistence = mock(KnowledgeVectorPersistenceService.class);
        var properties = new AppProperties();
        var chunker = new MarkdownKnowledgeChunker(properties);
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenReturn("halfvec(512)");
        when(embedding.signature()).thenReturn("provider");
        when(embedding.embedForSignatureWithUsage("标题\n\n正文", "provider:halfvec-v1"))
                .thenReturn(new KnowledgeEmbeddingService.EmbeddingResult(new float[]{1,0},17L,17L));
        when(embedding.toPgVector(any())).thenReturn("[1,0]");
        var service = new PgVectorKnowledgeService(jdbc,embedding,properties,persistence,chunker,new VectorCompressionService(properties),mock(KnowledgeSourceSyncStore.class));
        service.add("admin","标题","正文");
        var captured = org.mockito.ArgumentCaptor.forClass(KnowledgeVectorPersistenceService.KnowledgeVectorRow.class);
        verify(persistence).add(eq("admin"),captured.capture());
        assertThat(captured.getValue().tokenCount()).isEqualTo(2);
        assertThat(captured.getValue().inputTokens()).isEqualTo(17L);
        assertThat(captured.getValue().totalTokens()).isEqualTo(17L);
        verify(embedding).embedForSignatureWithUsage("标题\n\n正文","provider:halfvec-v1");
        verify(embedding,never()).embedForSignature(anyString(),anyString());
    }

    @Test void sourceSynchronizationPublishesReportedUsageWithNewChunks() {
        var jdbc = mock(JdbcTemplate.class);
        var embedding = mock(KnowledgeEmbeddingService.class);
        var source = mock(KnowledgeSourceSyncStore.class);
        var properties = new AppProperties();
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenReturn("halfvec(512)");
        when(embedding.signature()).thenReturn("provider");
        when(embedding.embedForSignatureWithUsage("标题\n\n正文","provider:halfvec-v1"))
                .thenReturn(new KnowledgeEmbeddingService.EmbeddingResult(new float[]{1,0},19L,19L));
        when(embedding.toPgVector(any())).thenReturn("[1,0]");
        var lease = new KnowledgeSourceSyncStore.Lease("diet_guides.md","fp","provider:halfvec-v1","node",java.util.UUID.randomUUID(),0);
        when(source.claim(anyString(),anyString(),anyString(),eq(true))).thenReturn(new KnowledgeSourceSyncStore.Admission(lease,0));
        var service = new PgVectorKnowledgeService(jdbc,embedding,properties,mock(KnowledgeVectorPersistenceService.class),new MarkdownKnowledgeChunker(properties),new VectorCompressionService(properties),source);
        service.syncSource("diet_guides.md","v1",LocalDateTime.now(),List.of(new PgVectorKnowledgeService.KnowledgeChunk("key","标题","正文",0,2,"hash")));
        org.mockito.Mockito.verify(source).publish(eq(lease),org.mockito.ArgumentMatchers.argThat(rows -> rows.size()==1
                && rows.get(0).inputTokens().equals(19L) && rows.get(0).totalTokens().equals(19L) && rows.get(0).vector().equals("[1,0]")),eq(List.of()));
        verify(embedding).embedForSignatureWithUsage("标题\n\n正文","provider:halfvec-v1");
    }

    @Test @SuppressWarnings("unchecked")
    void initialUpgradeDoesNotOverwriteExistingManualContent() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var embedding=mock(KnowledgeEmbeddingService.class);
        var store=mock(KnowledgeSourceSyncStore.class);var properties=new AppProperties();var existing=mock(ResultSet.class);
        when(existing.getLong("id")).thenReturn(1L);when(existing.getString("chunk_key")).thenReturn("key");
        when(existing.getString("title")).thenReturn("title");when(existing.getString("content")).thenReturn("human edit");
        when(jdbc.query(anyString(),any(RowMapper.class),eq("diet_guides.md"))).thenAnswer(call->List.of(((RowMapper<?>)call.getArgument(1)).mapRow(existing,0)));
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenReturn("halfvec(512)");when(embedding.signature()).thenReturn("provider");
        var lease=new KnowledgeSourceSyncStore.Lease("diet_guides.md","fingerprint","provider:halfvec-v1","node",java.util.UUID.randomUUID(),0);
        when(store.claim(anyString(),anyString(),anyString(),eq(false))).thenReturn(new KnowledgeSourceSyncStore.Admission(lease,0));
        var service=new PgVectorKnowledgeService(jdbc,embedding,properties,mock(KnowledgeVectorPersistenceService.class),
                mock(MarkdownKnowledgeChunker.class),new VectorCompressionService(properties),store);
        var chunk=new PgVectorKnowledgeService.KnowledgeChunk("key","title","bundled body",0,2,"hash");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.syncSource("diet_guides.md","v1",LocalDateTime.now(),List.of(chunk),false))
                .isInstanceOf(KnowledgeSourceSyncStore.Conflict.class);
        verify(embedding,never()).embed(anyString());verify(store).release(lease,"REVIEW_REQUIRED");
    }

    @Test void completedSourceSkipsEmbeddingsAndPublicationOnAnotherInstance() {
        var jdbc=mock(JdbcTemplate.class);var embedding=mock(KnowledgeEmbeddingService.class);
        var store=mock(KnowledgeSourceSyncStore.class);var properties=new AppProperties();
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenReturn("halfvec(512)");
        when(embedding.signature()).thenReturn("provider");
        when(store.claim(anyString(),anyString(),anyString(),eq(false))).thenReturn(new KnowledgeSourceSyncStore.Admission(null,100));
        var service=new PgVectorKnowledgeService(jdbc,embedding,properties,mock(KnowledgeVectorPersistenceService.class),
                mock(MarkdownKnowledgeChunker.class),new VectorCompressionService(properties),store);
        assertThat(service.syncSource("diet_guides.md","v1",LocalDateTime.now(),List.of(),false).unchanged()).isEqualTo(100);
        verify(embedding,never()).embed(anyString());verify(store,never()).publish(any(),any(),any());
    }
    @Test void competingSourceNeverStartsEmbeddingWork() {
        var jdbc=mock(JdbcTemplate.class);var embedding=mock(KnowledgeEmbeddingService.class);
        var store=mock(KnowledgeSourceSyncStore.class);var properties=new AppProperties();
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenReturn("halfvec(512)");
        when(embedding.signature()).thenReturn("provider");
        when(store.claim(anyString(),anyString(),anyString(),eq(false))).thenThrow(new KnowledgeSourceSyncStore.Conflict(KnowledgeSourceSyncStore.Reason.BUSY));
        var service=new PgVectorKnowledgeService(jdbc,embedding,properties,mock(KnowledgeVectorPersistenceService.class),
                mock(MarkdownKnowledgeChunker.class),new VectorCompressionService(properties),store);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.syncSource("diet_guides.md","v1",LocalDateTime.now(),List.of(),false))
                .isInstanceOf(KnowledgeSourceSyncStore.Conflict.class);
        verify(embedding,never()).embed(anyString());verify(store,never()).publish(any(),any(),any());
    }

    @Test void sourceFingerprintChangesWithContentVersionAndEmbeddingConfiguration() {
        var first=new PgVectorKnowledgeService.KnowledgeChunk("key","title","body",0,2,"hash");
        var edited=new PgVectorKnowledgeService.KnowledgeChunk("key","title","edited",0,2,"hash");
        String fingerprint=PgVectorKnowledgeService.sourceFingerprint("v1","signature",List.of(first));
        assertThat(fingerprint).isEqualTo(PgVectorKnowledgeService.sourceFingerprint("v1","signature",List.of(first)));
        assertThat(fingerprint).isNotEqualTo(PgVectorKnowledgeService.sourceFingerprint("v2","signature",List.of(first)))
                .isNotEqualTo(PgVectorKnowledgeService.sourceFingerprint("v1","new-provider",List.of(first)))
                .isNotEqualTo(PgVectorKnowledgeService.sourceFingerprint("v1","signature",List.of(edited)));
    }

    @Test
    void usesWeightedRrfInsteadOfMixingRawRetrieverScores() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        AppProperties properties = new AppProperties();
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("halfvec(512)");
        when(embeddingService.signature()).thenReturn("semantic-v1");
        when(embeddingService.embedForSignature(eq("减脂晚餐"),anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingService.toPgVector(any(float[].class))).thenReturn("[0.1,0.2]");
        PgVectorKnowledgeService service = new PgVectorKnowledgeService(
                jdbcTemplate,
                embeddingService,
                properties,
                mock(KnowledgeVectorPersistenceService.class),
                mock(MarkdownKnowledgeChunker.class),
                new VectorCompressionService(properties), mock(KnowledgeSourceSyncStore.class)
        );

        service.search("减脂晚餐", 5);

        String sql = mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> "query".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.getArgument(0, String.class))
                .filter(value -> value.contains("vector_candidates AS ("))
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
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("halfvec(512)");
        var sourceSync=mock(KnowledgeSourceSyncStore.class);
        var lease=new KnowledgeSourceSyncStore.Lease("diet_guides.md","fingerprint","semantic-v1:halfvec-v1","node",java.util.UUID.randomUUID(),0);
        when(sourceSync.claim(anyString(),anyString(),anyString(),eq(true))).thenReturn(new KnowledgeSourceSyncStore.Admission(lease,0));

        PgVectorKnowledgeService service = new PgVectorKnowledgeService(
                jdbcTemplate, embeddingService, new AppProperties(), persistenceService, chunker,
                new VectorCompressionService(new AppProperties()),sourceSync);
        PgVectorKnowledgeService.KnowledgeChunk chunk = new PgVectorKnowledgeService.KnowledgeChunk(
                "stable-key", "标题", "正文", 0, 2, "same-hash");

        PgVectorKnowledgeService.SyncResult result = service.syncSource(
                "diet_guides.md", "v1", LocalDateTime.now(), List.of(chunk));

        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        verify(embeddingService, never()).embed(anyString());
        verify(embeddingService, never()).embedForSignatureWithUsage(anyString(),anyString());
        verify(sourceSync).publish(eq(lease), any(), eq(List.of()));
    }

    @Test
    void beanStartupDoesNotMigrateOrCallEmbeddingProvider() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embedding = mock(KnowledgeEmbeddingService.class);
        KnowledgeVectorPersistenceService persistence = mock(KnowledgeVectorPersistenceService.class);
        AppProperties properties = new AppProperties();
        var service = new PgVectorKnowledgeService(jdbc, embedding, properties, persistence,
                mock(MarkdownKnowledgeChunker.class), new VectorCompressionService(properties),mock(KnowledgeSourceSyncStore.class));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(PgVectorKnowledgeService.class, () -> service);
            context.refresh();
            org.mockito.Mockito.verifyNoInteractions(jdbc, embedding, persistence);
        }
    }

    @Test
    void incompatibleVectorStorageKeepsKeywordSearchAvailableWithoutModelCalls() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        KnowledgeEmbeddingService embedding = mock(KnowledgeEmbeddingService.class);
        AppProperties properties = new AppProperties();
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("vector(384)");
        var service = new PgVectorKnowledgeService(jdbc, embedding, properties,
                mock(KnowledgeVectorPersistenceService.class), mock(MarkdownKnowledgeChunker.class), new VectorCompressionService(properties),mock(KnowledgeSourceSyncStore.class));
        service.search("晚餐", 5);
        verify(embedding, never()).embed(anyString());
        String sql = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> "query".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.getArgument(0, String.class)).findFirst().orElseThrow();
        assertThat(sql).contains("keyword_title_candidates", "keyword_content_candidates").doesNotContain("<=>");
    }
}
