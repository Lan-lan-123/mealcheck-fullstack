package com.example.mealcheck.controller;
import com.example.mealcheck.security.JwtAuthenticationFilter;
import com.example.mealcheck.service.KnowledgeContentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=KnowledgeContentController.class,excludeFilters=@ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=JwtAuthenticationFilter.class))
@Import(KnowledgeIndexingControllerTest.TestSecurity.class)
class KnowledgeContentControllerTest {
    @Autowired MockMvc http;
    @MockitoBean KnowledgeContentService service;
    @Test @WithMockUser(roles="USER") void ordinaryUserCannotReadOrRestoreHistory() throws Exception {
        http.perform(get("/api/admin/knowledge/content-history")).andExpect(status().isForbidden());
        http.perform(get("/api/admin/knowledge/content-history/1")).andExpect(status().isForbidden());
        http.perform(post("/api/admin/knowledge/content-history/1/restore").contentType("application/json").content("{\"expectedRevision\":2,\"reason\":\"restore\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test @WithMockUser(roles="ADMIN") void administratorCanReadDeletedHistoryAndRestoreWithReason() throws Exception {
        when(service.history(null,true,0,10)).thenReturn(Map.of("content",List.of(),"totalElements",0));
        http.perform(get("/api/admin/knowledge/content-history?deletedOnly=true")).andExpect(status().isOk());
        http.perform(post("/api/admin/knowledge/content-history/1/restore").contentType("application/json").content("{\"expectedRevision\":2,\"reason\":\"restore\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.restored").value(true));
        verify(service).restore(1,2,"unknown","restore");
    }
    @Test @WithMockUser(roles="ADMIN") void restoreRequiresVersionAndReason() throws Exception {
        for(String json:List.of("{}","{\"expectedRevision\":0,\"reason\":\"restore\"}","{\"expectedRevision\":2,\"reason\":\" \"}"))
            http.perform(post("/api/admin/knowledge/content-history/1/restore").contentType("application/json").content(json)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
