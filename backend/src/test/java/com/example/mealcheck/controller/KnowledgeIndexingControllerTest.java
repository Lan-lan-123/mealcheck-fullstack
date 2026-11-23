package com.example.mealcheck.controller;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.security.JwtAuthenticationFilter;
import com.example.mealcheck.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=KnowledgeIndexingController.class, excludeFilters=
        @ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE, classes=JwtAuthenticationFilter.class))
@Import(KnowledgeIndexingControllerTest.TestSecurity.class)
@EnableConfigurationProperties(AppProperties.class)
class KnowledgeIndexingControllerTest {
    @org.junit.jupiter.api.BeforeEach void dimensions() {
        when(embeddings.dimensionForMode(anyString())).thenReturn(512);
        when(embeddings.activeDimension()).thenReturn(512);
    }
    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurity {
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }
    @Autowired MockMvc http;
    @MockitoBean KnowledgeEmbeddingService embeddings;
    @MockitoBean KnowledgeRebuildStore store;
    @MockitoBean VectorCompressionService compression;
    @MockitoBean KnowledgeJobLifecycleService lifecycle;
    @MockitoBean KnowledgeIndexService index;
    @MockitoBean KnowledgeRebuildPreviewService previews;

    @Test @WithMockUser(roles="USER") void ordinaryUserCannotPreview() throws Exception {
        http.perform(post("/api/admin/knowledge/indexing/preview").contentType("application/json").content("{\"mode\":\"LOCAL\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(previews);
    }
    @Test @WithMockUser(roles="ADMIN") void stalePreviewReturnsConflictWithoutSubmittingJob() throws Exception {
        when(previews.submit(eq("LOCAL"),isNull(),isNull(),eq("stale"),anyString()))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请重新预览"));
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content("{\"mode\":\"LOCAL\",\"previewToken\":\"stale\"}"))
            .andExpect(status().isConflict());
        verifyNoInteractions(store);
    }

    @Test void anonymousCannotInspectOrStartBuilds() throws Exception {
        http.perform(get("/api/admin/knowledge/indexing")).andExpect(status().is4xxClientError());
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content("{\"mode\":\"LOCAL\"}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(store);
    }
    @Test @WithMockUser(roles="USER") void ordinaryUserCannotInspectOrStartBuilds() throws Exception {
        http.perform(get("/api/admin/knowledge/indexing")).andExpect(status().isForbidden());
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content("{\"mode\":\"LOCAL\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }
    @Test @WithMockUser(roles="USER") void ordinaryUserCannotRecoverJobsOrReadHistory() throws Exception {
        http.perform(get("/api/admin/knowledge/indexing/jobs")).andExpect(status().isForbidden());
        http.perform(get("/api/admin/knowledge/indexing/jobs/7")).andExpect(status().isForbidden());
        for(String action:List.of("cancel","retry","resubmit"))
            http.perform(post("/api/admin/knowledge/indexing/jobs/7/"+action)).andExpect(status().isForbidden());
        verifyNoInteractions(store,lifecycle);
    }
    @Test @WithMockUser(roles="ADMIN") void administratorCanInspectCancelRetryAndResubmit() throws Exception {
        when(store.history(1,8)).thenReturn(Map.of("content",List.of(),"totalElements",0));
        when(store.details(7)).thenReturn(Map.of("id",7,"events",List.of()));
        when(store.status(7)).thenReturn(Map.of("id",7,"status","CANCELLED"));
        when(lifecycle.resubmit(eq(7L),eq("LOCAL"),isNull(),eq("reviewed"),anyString())).thenReturn(8L);
        when(store.status(8)).thenReturn(Map.of("id",8,"status","PENDING"));
        http.perform(get("/api/admin/knowledge/indexing/jobs?page=1&size=8")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        http.perform(get("/api/admin/knowledge/indexing/jobs/7")).andExpect(status().isOk()).andExpect(jsonPath("$.events").isArray());
        http.perform(post("/api/admin/knowledge/indexing/jobs/7/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        http.perform(post("/api/admin/knowledge/indexing/jobs/7/retry")).andExpect(status().isAccepted());
        http.perform(post("/api/admin/knowledge/indexing/jobs/7/resubmit").contentType("application/json").content("{\"mode\":\"LOCAL\",\"previewToken\":\"reviewed\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(8));
        verify(store).cancel(7);verify(lifecycle).retry(7);verify(lifecycle).resubmit(eq(7L),eq("LOCAL"),isNull(),eq("reviewed"),anyString());
    }
    @Test @WithMockUser(roles="ADMIN") void unconfiguredSemanticAndUnknownModeAreRejected() throws Exception {
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content("{\"mode\":\"SEMANTIC\"}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content("{\"mode\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test @WithMockUser(roles="ADMIN") void localBuildReturnsDurableJobWithoutChangingActiveMode() throws Exception {
        when(embeddings.signatureForMode("LOCAL")).thenReturn("hash-v1:512");
        when(compression.signatureSuffix()).thenReturn("halfvec-v1");
        when(compression.storageType()).thenReturn(VectorCompressionService.StorageType.HALFVEC);
        when(index.activeSplitterStrategy()).thenReturn("recursive-markdown");
        when(previews.submit(eq("LOCAL"),isNull(),isNull(),isNull(),anyString())).thenReturn(42L);
        when(store.status(42L)).thenReturn(Map.of("id",42,"status","PENDING"));
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content("{\"mode\":\"LOCAL\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/knowledge/rebuilds/42"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(embeddings,never()).embed(anyString());
    }
    @Test @WithMockUser(roles="ADMIN") void statusExposesAvailabilityWithoutCredentials() throws Exception {
        when(embeddings.activeMode()).thenReturn("LOCAL");
        when(embeddings.semanticModel()).thenReturn(KnowledgeEmbeddingService.QWEN_FLASH);
        when(embeddings.isSemanticAvailable()).thenReturn(true);
        when(index.activeSplitterStrategy()).thenReturn("structural-markdown");
        http.perform(get("/api/admin/knowledge/indexing")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMode").value("LOCAL"))
                .andExpect(jsonPath("$.semanticAvailable").value(true))
                .andExpect(jsonPath("$.dimension").value(512))
                .andExpect(jsonPath("$.activeSplitterStrategy").value("structural-markdown"))
                .andExpect(jsonPath("$.splitterStrategies.length()").value(4))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test @WithMockUser(roles="ADMIN") void fullBuildCapturesEachSelectedSplitter() throws Exception {
        when(embeddings.signatureForMode("LOCAL")).thenReturn("hash-v1:512");
        when(compression.signatureSuffix()).thenReturn("halfvec-v1");
        when(compression.storageType()).thenReturn(VectorCompressionService.StorageType.HALFVEC);
        for (String splitter : MarkdownKnowledgeChunker.SELECTABLE_STRATEGIES) {
            when(previews.submit(eq("LOCAL"),isNull(),eq(splitter),isNull(),anyString())).thenReturn(44L);
            when(store.status(44)).thenReturn(Map.of("id",44,"splitter_strategy",splitter));
            http.perform(post("/api/admin/knowledge/indexing").contentType("application/json")
                    .content("{\"mode\":\"LOCAL\",\"splitterStrategy\":\""+splitter+"\"}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.splitter_strategy").value(splitter));
            verify(previews).submit(eq("LOCAL"),isNull(),eq(splitter),isNull(),anyString());
        }
        verify(embeddings,never()).embed(anyString());
    }

    @Test @WithMockUser(roles="ADMIN") void invalidOrSelectedChunkSplitterIsRejected() throws Exception {
        for (String body : List.of("{\"mode\":\"LOCAL\",\"splitterStrategy\":\"typo\"}",
                "{\"mode\":\"LOCAL\",\"chunkIds\":[1],\"splitterStrategy\":\"structural-markdown\"}")) {
            http.perform(post("/api/admin/knowledge/indexing").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
    }
    @Test @WithMockUser(roles="ADMIN") void selectedBuildCannotIntroduceAnotherVectorSpace() throws Exception {
        when(embeddings.activeMode()).thenReturn("SEMANTIC");
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json")
                .content("{\"mode\":\"LOCAL\",\"chunkIds\":[1,2]}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(store);
    }
    @Test @WithMockUser(roles="ADMIN") void selectedBuildCapturesOnlyRequestedChunks() throws Exception {
        when(embeddings.activeMode()).thenReturn("SEMANTIC");
        when(embeddings.isSemanticAvailable()).thenReturn(true);
        when(embeddings.signatureForMode("SEMANTIC")).thenReturn("semantic-v1:fixture:512:abc");
        when(compression.signatureSuffix()).thenReturn("halfvec-v1");
        when(compression.storageType()).thenReturn(VectorCompressionService.StorageType.HALFVEC);
        when(previews.submit(eq("SEMANTIC"),eq(List.of(1L,2L)),isNull(),isNull(),anyString())).thenReturn(43L);
        when(store.status(43L)).thenReturn(Map.of("id",43,"scope","SELECTED","totalChunks",2));
        http.perform(post("/api/admin/knowledge/indexing").contentType("application/json")
                .content("{\"mode\":\"SEMANTIC\",\"chunkIds\":[1,2]}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.totalChunks").value(2));
        verify(store,never()).requestBuild(anyString(),anyInt(),anyString(),anyString());
        verify(embeddings,never()).embed(anyString());
    }
}
