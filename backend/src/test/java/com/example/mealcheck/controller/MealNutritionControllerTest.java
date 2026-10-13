package com.example.mealcheck.controller;

import com.example.mealcheck.service.MealNutritionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MealNutritionControllerTest {
    private final MealNutritionService service = mock(MealNutritionService.class);
    private MockMvc http;
    @BeforeEach void setup() {
        http = MockMvcBuilders.standaloneSetup(new MealNutritionController(service))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void rejectsMissingOptInInvalidWeightsAndNegativeNutrientData() throws Exception {
        for (String body : new String[]{
                "{\"expectedRevision\":0,\"expectedVersion\":0,\"items\":[]}",
                "{\"enabled\":true,\"items\":[{\"foodIndex\":0,\"grams\":0}]}",
                "{\"enabled\":true,\"items\":[{\"foodIndex\":0,\"grams\":10001}]}",
                "{\"enabled\":true,\"items\":[{\"foodIndex\":0,\"per100g\":{\"energyKcal\":-1}}]}",
                "{\"enabled\":true,\"items\":[{\"foodIndex\":0,\"grams\":10.001}]}",
                "{\"enabled\":false,\"expectedVersion\":-1,\"items\":[]}"}) {
            http.perform(put("/api/meals/1/nutrition").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }
}
