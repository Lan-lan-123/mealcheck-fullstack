package com.example.mealcheck.controller;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.MealPlanningDtos.*;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.security.*;
import com.example.mealcheck.service.*;
import com.example.mealcheck.util.AppTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=MealPlanningController.class, excludeFilters=
        @ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE, classes=JwtAuthenticationFilter.class))
@Import(MealPlanningControllerTest.TestSecurity.class)
@EnableConfigurationProperties(AppProperties.class)
class MealPlanningControllerTest {
    @TestConfiguration static class TestSecurity {
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
    }
    @Autowired MockMvc http;
    @MockBean TodayMealService today;
    @MockBean NextMealService next;
    UserPrincipal principal() { var account=new UserAccount(); account.setId(9L); account.setUsername("owner"); return new UserPrincipal(account); }
    @Test void anonymousRequestsAreRejected() throws Exception {
        http.perform(get("/api/meals/today")).andExpect(status().is4xxClientError());
        http.perform(post("/api/meals/next-meal").contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(today,next);
    }
    @Test void todayUsesAuthenticatedOwnerRatherThanQueryUserId() throws Exception {
        when(today.get(9)).thenReturn(new Today(AppTime.today(),"Asia/Shanghai",0,Map.of(),0,0,List.of(),0,List.of()));
        http.perform(get("/api/meals/today?userId=999").with(user(principal())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMeals").value(0));
        verify(today).get(9);
    }
    @Test void invalidRequestDoesNotReachRecommendationService() throws Exception {
        for (String body : List.of("{}", "{\"mealType\":\"SNACK\",\"scene\":\"HOME\",\"budget\":20,\"availableMinutes\":30}",
                "{\"mealType\":\"LUNCH\",\"scene\":\"HOME\",\"budget\":0,\"availableMinutes\":3}")) {
            http.perform(post("/api/meals/next-meal").with(user(principal())).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(next);
    }
    @Test void validRequestPassesOnlyAuthenticatedIdentityAndValidatedConstraints() throws Exception {
        http.perform(post("/api/meals/next-meal").with(user(principal())).contentType("application/json")
                .content("{\"userId\":999,\"mealType\":\"LUNCH\",\"scene\":\"CANTEEN\",\"budget\":20,\"availableMinutes\":30}"))
                .andExpect(status().isOk());
        verify(next).recommend(org.mockito.ArgumentMatchers.argThat(value -> value.getId()==9),
                org.mockito.ArgumentMatchers.eq(new NextMealRequest("LUNCH","CANTEEN",20,30)));
    }
}
