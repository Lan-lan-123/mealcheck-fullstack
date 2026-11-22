package com.example.mealcheck.controller;

import com.example.mealcheck.security.JwtAuthenticationFilter;
import com.example.mealcheck.service.KnowledgeEmbeddingConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=KnowledgeEmbeddingSettingsController.class,excludeFilters=
        @ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=JwtAuthenticationFilter.class))
@Import(KnowledgeIndexingControllerTest.TestSecurity.class)
class KnowledgeEmbeddingSettingsControllerTest {
    @Autowired MockMvc http;
    @MockBean KnowledgeEmbeddingConfigurationService settings;
    @Test void anonymousCannotReadCredentialsConfiguration() throws Exception {
        http.perform(get("/api/admin/knowledge/embedding-settings")).andExpect(status().is4xxClientError());
        verifyNoInteractions(settings);
    }
    @Test @WithMockUser(roles="USER") void usersCannotReadSaveTestOrApply() throws Exception {
        http.perform(get("/api/admin/knowledge/embedding-settings")).andExpect(status().isForbidden());
        http.perform(put("/api/admin/knowledge/embedding-settings").contentType("application/json")
                .content("{\"model\":\"m\",\"baseUrl\":\"https://provider.test/v1\",\"apiKey\":\"key\",\"dimension\":512,\"sendDimensions\":true,\"version\":0}")).andExpect(status().isForbidden());
        for (String action:java.util.List.of("test","apply"))
            http.perform(post("/api/admin/knowledge/embedding-settings/"+action).contentType("application/json").content("{\"version\":0}")).andExpect(status().isForbidden());
        verifyNoInteractions(settings);
    }
    @Test @WithMockUser(roles="ADMIN") void invalidDimensionsAreRejectedBeforeSaving() throws Exception {
        for(int dimension:java.util.List.of(0,2001))
            http.perform(put("/api/admin/knowledge/embedding-settings").contentType("application/json")
                    .content("{\"model\":\"m\",\"baseUrl\":\"https://provider.test/v1\",\"dimension\":"+dimension+",\"version\":0}")).andExpect(status().isBadRequest());
        verifyNoInteractions(settings);
    }
    @Test @WithMockUser(roles="ADMIN") void administratorCanReadSaveTestAndApply() throws Exception {
        http.perform(get("/api/admin/knowledge/embedding-settings")).andExpect(status().isOk());
        http.perform(put("/api/admin/knowledge/embedding-settings").contentType("application/json")
                .content("{\"model\":\"m\",\"baseUrl\":\"https://provider.test/v1\",\"dimension\":512,\"sendDimensions\":true,\"version\":0}")).andExpect(status().isOk());
        for(String action:java.util.List.of("test","apply"))
            http.perform(post("/api/admin/knowledge/embedding-settings/"+action).contentType("application/json").content("{\"version\":0}")).andExpect(status().isOk());
        verify(settings).view(); verify(settings).save(any(),any()); verify(settings).test(any(),any()); verify(settings).apply(any(),any());
    }
}
