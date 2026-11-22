package com.example.mealcheck.controller;

import com.example.mealcheck.config.*;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.*;
import com.example.mealcheck.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiCostSettingsController.class)
@Import({CsrfTestRequests.class,SecurityConfig.class,JwtService.class,JwtAuthenticationFilter.class,CustomUserDetailsService.class})
@EnableConfigurationProperties(AppProperties.class)
class AiCostSettingsHttpTest {
    @Autowired MockMvc http;
    @Autowired JwtService jwt;
    @MockBean AiCostSettingsService settings;
    @MockBean UserAccountRepository users;
    @MockBean TokenBlacklistService blacklist;
    String token,adminToken;
    @BeforeEach void setup() {
        token=token(10L,"cost-user","USER");adminToken=token(20L,"cost-admin","ADMIN");
    }
    String token(Long id,String name,String role) {
        var user=new UserAccount();user.setId(id);user.setUsername(name);user.setRole(role);user.setPasswordHash("hash");
        when(users.findByUsername(name)).thenReturn(Optional.of(user));when(users.findById(id)).thenReturn(Optional.of(user));
        return jwt.generateToken(new UserPrincipal(user));
    }
    @Test void anonymousUsersAreRejectedAndOrdinaryUsersCannotEditGlobalPolicyOrPrices() throws Exception {
        http.perform(get("/api/profile/ai-cost-settings")).andExpect(status().isUnauthorized());
        http.perform(put("/api/profile/ai-cost-settings").contentType("application/json").content("{\"version\":0,\"dailyCost\":1}"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/admin/ai-cost-settings").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        http.perform(put("/api/admin/ai-cost-settings").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"version\":0,\"globalDailyCost\":0,\"userDailyCost\":0,\"systemDailyCost\":0,\"prices\":[]}"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/admin/ai-cost-settings/reset").header("Authorization","Bearer "+token).contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(settings);
    }
    @Test void personalBudgetUsesOnlyTheAuthenticatedIdentity() throws Exception {
        var view=new AiCostSettingsService.PersonalView(1,true,"CNY",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE);
        when(settings.personalView(10L)).thenReturn(view);when(settings.savePersonal(eq(10L),any())).thenReturn(view);
        http.perform(get("/api/profile/ai-cost-settings?userId=999").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dailyCost").value(1));
        http.perform(put("/api/profile/ai-cost-settings?userId=999").header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"version\":0,\"dailyCost\":1,\"userId\":999}"))
                .andExpect(status().isOk());
        verify(settings).savePersonal(eq(10L),argThat(r->r.dailyCost().compareTo(BigDecimal.ONE)==0));
    }
    @Test void invalidAndMissingAmountsAreRejectedBeforeSaving() throws Exception {
        for(String value:List.of("-1","null","1000000000","0.0000001"))
            http.perform(put("/api/profile/ai-cost-settings").header("Authorization","Bearer "+token)
                    .contentType("application/json").content("{\"version\":0,\"dailyCost\":"+value+"}"))
                    .andExpect(status().isBadRequest());
        http.perform(put("/api/admin/ai-cost-settings").header("Authorization","Bearer "+adminToken)
                .contentType("application/json").content("{\"version\":0,\"globalDailyCost\":-1,\"userDailyCost\":0,\"systemDailyCost\":0,\"prices\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(settings);
    }
    @Test void administratorCanSaveAndRestoreGlobalSettings() throws Exception {
        var view=new AiCostSettingsService.View(1,true,"CNY","ADMIN",BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,List.of());
        when(settings.save(any(),any())).thenReturn(view);when(settings.reset(any(),any())).thenReturn(view);
        http.perform(put("/api/admin/ai-cost-settings").header("Authorization","Bearer "+adminToken).contentType("application/json")
                .content("{\"version\":0,\"globalDailyCost\":10,\"userDailyCost\":1,\"systemDailyCost\":1,\"prices\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.globalDailyCost").value(10));
        verify(settings).save(any(),argThat(p->p.getId().equals(20L)));
        http.perform(post("/api/admin/ai-cost-settings/reset").header("Authorization","Bearer "+adminToken).contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isOk());
        verify(settings).reset(any(),argThat(p->p.getId().equals(20L)));
    }
}
