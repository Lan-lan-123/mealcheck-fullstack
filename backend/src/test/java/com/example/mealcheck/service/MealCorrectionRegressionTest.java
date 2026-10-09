package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MealCorrectionRegressionTest {
    @Test void reviewedFixturesMeetTheirHumanExpectedScores() throws Exception {
        var mapper=new ObjectMapper();
        String path=System.getProperty("mealcheck.regression.dataset");
        var root=path == null ? mapper.readTree(getClass().getResourceAsStream("/evaluation/meal_correction_regression.json"))
                : mapper.readTree(Files.readString(Path.of(path)));
        assertThat(root.path("cases").size()).as("An empty dataset cannot pass the release gate").isGreaterThan(0);
        Set<String> ids=new HashSet<>();
        for (var fixture: root.path("cases")) {
            assertThat(ids.add(fixture.path("id").asText())).as("Unique fixture IDs").isTrue();
            var recognition=new RecognitionResult();
            recognition.setFoods(mapper.convertValue(fixture.path("foods"),new TypeReference<List<FoodItem>>() {}));
            // Exported fixtures contain human confirmed foods, without provider confidence.
            recognition.getFoods().forEach(food -> food.setConfidence(1));
            assertThat(new MealScoringService().evaluate(recognition,fixture.path("goal").asText()).getScore())
                    .as(fixture.path("id").asText()).isEqualTo(fixture.path("expectedScore").asInt());
        }
    }
}
