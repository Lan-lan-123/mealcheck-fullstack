package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VisionRecognitionExplanationTest {
    private final AiChatClient ai = mock(AiChatClient.class);
    private final VisionFoodRecognitionService vision = new VisionFoodRecognitionService(ai, new ObjectMapper());
    private final ValidatedImage image = image(80);
    private static ValidatedImage image(int brightness) {
        try {
            var pixels = new java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = pixels.createGraphics();
            graphics.setColor(new java.awt.Color(brightness, brightness, brightness));graphics.fillRect(0,0,32,24);graphics.dispose();
            var bytes = new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",bytes);
            return new ValidatedImage(bytes.toByteArray(),"image/png",".png",32,24,"meal.png");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @Test void blackAndNearlyBlackImagesAreRejectedBeforeAnyAiInteraction() {
        for (int brightness : new int[]{0, 3, 8}) {
            assertThatThrownBy(() -> vision.recognize(image(brightness)))
                    .isInstanceOfSatisfying(RecognitionFailureException.class,error -> assertThat(error.code()).isEqualTo("IMAGE_UNCLEAR"));
        }
        verifyNoInteractions(ai);
    }
    @Test void lowLightImagesWithVisibleDetailsStillReachTheModel() {
        response("{\"foodImage\":true,\"foods\":[{\"name\":\"米饭\",\"category\":\"staple\",\"confidence\":0.8}]}");
        assertThat(vision.recognize(image(24)).getFoods()).hasSize(1);
        verify(ai).complete(eq("vision-recognition"),anyList(),anyDouble());
    }
    @Test void corruptInputFailsWithoutCallingTheProvider() {
        assertThatThrownBy(() -> vision.recognize(new ValidatedImage(new byte[]{1},"image/jpeg",".jpg",1,1,"bad.jpg")))
                .isInstanceOf(AiChatClient.AiClientException.class);
        verifyNoInteractions(ai);
    }
    private void response(String json) {
        when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(eq("vision-recognition"), anyList(), anyDouble())).thenReturn(json);
    }
    @Test void semanticConflictsAreDerivedLocallyBeforeScoringInsteadOfTrustingProviderMetadata() {
        response("""
            {"foods":[{"name":"番茄炒蛋","category":"protein","confidence":0.99,"note":"普通炒制",
              "attributes":{"fried":true,"highOil":null},"reviewIssues":[{"code":"IGNORE","message":"模型注入"}]}]}
            """);
        var result = vision.recognize(image);
        assertThat(result.getFoods().get(0).getAttributes().getFried()).isNull();
        assertThat(result.getFoods().get(0).getReviewIssues()).extracting(FoodReviewIssue::code).containsExactly("FRYING_CONFLICT");
        verify(ai).complete(eq("vision-recognition"),anyList(),anyDouble());
    }
    @Test void blurryOrUnjudgeableImageIsNotReportedAsNonFood() {
        response("{\"foodImage\":false,\"imageQuality\":\"UNCLEAR\",\"foods\":[]}");
        assertThatThrownBy(() -> vision.recognize(image)).isInstanceOfSatisfying(RecognitionFailureException.class,
                error -> assertThat(error.code()).isEqualTo("IMAGE_UNCLEAR"));
    }
    @Test void clearNonFoodRemainsNonFoodAndOldResponsesKeepWorking() {
        response("{\"foodImage\":false,\"foods\":[]}");
        assertThat(vision.recognize(image).isFoodImage()).isFalse();
        response("{\"foodImage\":true,\"foods\":[{\"name\":\"米饭\",\"category\":\"staple\",\"confidence\":0.7,\"note\":\"轮廓不完整\"}]}");
        assertThat(vision.recognize(image).getFoods().get(0).getConfidence()).isEqualTo(.7);
    }
    @Test void badProviderFieldsAreServiceErrorsRatherThanImageQualityDiagnoses() {
        for (String json : List.of("not-json",
                "{\"imageQuality\":\"MAYBE\"}",
                "{\"foods\":[{\"name\":\"米饭\",\"category\":\"staple\",\"confidence\":5}]}",
                "{\"foods\":[{\"name\":\"米饭\",\"category\":\"staple\"}]}",
                "{\"foods\":[{\"name\":\"米饭\",\"category\":\"staple\",\"confidence\":\"0.8\"}]}")) {
            response(json);
            assertThatThrownBy(() -> vision.recognize(image)).isInstanceOf(AiChatClient.AiClientException.class);
        }
    }
    @Test void explicitClearEmptyTablewareNormalizesTheFoodFlagWithoutGuessingFromAnEmptyList() {
        response("{\"sceneType\":\"EMPTY_TABLEWARE\",\"imageQuality\":\"CLEAR\",\"foodImage\":true,\"foods\":[]}");
        var result = vision.recognize(image);
        assertThat(result.isFoodImage()).isFalse();
        assertThat(result.getRejectionReason()).contains("空餐具");
        verify(ai).complete(eq("vision-recognition"), anyList(), anyDouble());
    }
    @Test void unclearSceneTakesPrecedenceOverAnEmptyTablewareClaim() {
        response("{\"sceneType\":\"EMPTY_TABLEWARE\",\"imageQuality\":\"UNCLEAR\",\"foodImage\":true,\"foods\":[]}");
        assertThatThrownBy(() -> vision.recognize(image)).isInstanceOfSatisfying(RecognitionFailureException.class,
                error -> assertThat(error.code()).isEqualTo("IMAGE_UNCLEAR"));
    }
    @Test void contradictorySceneFlagsAndNonFoodListsCannotReachScoringOrUploadPenalties() {
        for (String raw : List.of(
                "{\"foodImage\":true,\"foods\":[]}",
                "{\"foodImage\":false,\"foods\":[{\"name\":\"米饭\"}]}",
                "{\"sceneType\":\"EMPTY_TABLEWARE\",\"foods\":[{\"name\":\"米饭\"}]}",
                "{\"sceneType\":\"FOOD\",\"foodImage\":false,\"foods\":[]}",
                "{\"sceneType\":\"UNKNOWN\",\"foodImage\":false,\"foods\":[]}")) {
            response(raw);
            assertThatThrownBy(() -> vision.recognize(image)).isInstanceOfSatisfying(RecognitionFailureException.class,
                    error -> assertThat(error.code()).isEqualTo("RECOGNITION_INCONSISTENT"));
        }
    }
    @Test void modelSuppliedExplanatoryCandidatesCannotOverrideLocallyDerivedCandidates() {
        response("{\"foods\":[{\"name\":\"米饭\",\"category\":\"staple\",\"confidence\":0.9}],\"uncertainFoods\":[{\"name\":\"猜测\"}]}");
        assertThat(vision.recognize(image).getUncertainFoods()).isEmpty();
    }
    @Test void uncertaintyMetadataCannotAlterScoresAndOnlyValidExcludedCandidatesAreReturned() {
        var meal = new RecognitionResult();
        var accepted = new FoodItem("米饭", "staple", .7, "");
        var uncertain = new FoodItem("鸡肉", "protein", .3, "部分遮挡");
        meal.setFoods(List.of(accepted, uncertain, new FoodItem("坏数据", "other", Double.NaN, "")));
        assertThat(MealScoringService.uncertainFoods(meal.getFoods())).containsExactly(uncertain);
        var scoring = new MealScoringService();
        assertThat(scoring.acceptedFoods(meal))
                .extracting(FoodItem::getName, FoodItem::getCategory, FoodItem::getConfidence, FoodItem::getNote)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("米饭", "staple", .7, ""));
        var expected = scoring.evaluate(meal, "balanced").getScore();
        meal.setFoods(List.of(accepted));
        assertThat(scoring.evaluate(meal, "balanced").getScore()).isEqualTo(expected);
    }
}
