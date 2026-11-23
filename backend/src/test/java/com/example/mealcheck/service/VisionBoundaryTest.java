package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Synthetic provider payloads exercise parsing/scoring boundaries, not actual image-model accuracy. */
class VisionBoundaryTest {
    final AiChatClient ai=mock(AiChatClient.class);
    final VisionFoodRecognitionService vision=new VisionFoodRecognitionService(ai,new ObjectMapper());
    final ValidatedImage image=VisionRecognitionExplanationTestImage.image();
    static Stream<org.junit.jupiter.params.provider.Arguments> foodCases() {
        return Stream.of(arguments("少量剩饭","staple",.85,"餐盘大部分为空，仍可见米饭"),
                arguments("瓶装水","drink",.9,"可读标签写有饮用水"),
                arguments("包装面包","staple",.9,"包装标签清晰可读"),
                arguments("青菜","vegetable",.65,"餐具遮挡部分菜叶"));
    }
    void respond(String raw) {
        when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(eq("vision-recognition"),anyList(),anyDouble())).thenReturn(raw);
    }
    @ParameterizedTest @MethodSource("foodCases")
    void visibleFoodAndDrinksStayFoodWhileUncertaintyNeverBecomesMeasuredIntake(String name,String category,double confidence,String note) throws Exception {
        var payload=new ObjectMapper().createObjectNode().put("sceneType","FOOD").put("imageQuality","CLEAR").put("foodImage",true);
        payload.putArray("foods").addObject().put("name",name).put("category",category).put("confidence",confidence).put("note",note);
        respond(payload.toString());var result=vision.recognize(image);
        assertThat(result.isFoodImage()).isTrue();assertThat(result.getFoods()).hasSize(1);
        assertThat(result.getFoods().get(0).getAttributes().getHighOil()).isNull();
        assertThat(result.getFoods().get(0).getConfidence()).isEqualTo(confidence);
        assertThat(new MealScoringService().acceptedFoods(result)).hasSize(confidence<.5?0:1);
    }
    @Test void multiPlateSceneKeepsVisibleFoodsAndExcludesTheOccludedGuessFromScoring() {
        respond("""
                {"sceneType":"FOOD","imageQuality":"CLEAR","foodImage":true,"foods":[
                {"name":"米饭","category":"staple","confidence":0.95},
                {"name":"青菜","category":"vegetable","confidence":0.9},
                {"name":"未确认菜品","category":"other","confidence":0.2,"note":"另一只盘子被盖住"}]}
                """);
        var result=vision.recognize(image);var scoring=new MealScoringService();
        assertThat(result.getFoods()).hasSize(3);
        assertThat(scoring.acceptedFoods(result)).extracting(FoodItem::getName).containsExactly("米饭","青菜");
        assertThat(MealScoringService.uncertainFoods(result.getFoods())).extracting(FoodItem::getName).containsExactly("未确认菜品");
    }
    @Test void obstructionOrExplicitUnclearSceneCannotBeReportedAsEmptyTableware() {
        for(String raw:List.of(
                "{\"sceneType\":\"UNCLEAR\",\"imageQuality\":\"CLEAR\",\"foodImage\":false,\"foods\":[]}",
                "{\"sceneType\":\"EMPTY_TABLEWARE\",\"imageQuality\":\"UNCLEAR\",\"foodImage\":false,\"foods\":[]}")) {
            respond(raw);
            assertThatThrownBy(()->vision.recognize(image)).isInstanceOfSatisfying(RecognitionFailureException.class,
                    e->assertThat(e.code()).isEqualTo("IMAGE_UNCLEAR"));
        }
    }
    @Test void newSceneProtocolCannotGuessClarityOrFoodFlagFromMissingFields() {
        for(String raw:List.of("{\"sceneType\":\"EMPTY_TABLEWARE\",\"foodImage\":false,\"foods\":[]}",
                "{\"sceneType\":\"EMPTY_TABLEWARE\",\"imageQuality\":\"CLEAR\",\"foods\":[]}")) {
            respond(raw);
            assertThatThrownBy(()->vision.recognize(image)).isInstanceOfSatisfying(AiChatClient.InvalidResponseException.class,
                    e->assertThat(TaskFailureClassifier.classify(e,"ANALYZE").code()).isEqualTo("MODEL_RESPONSE_INVALID"));
        }
    }
    @Test void onlyStainsWithExplicitClearEmptySceneRemainNonFood() {
        respond("{\"sceneType\":\"EMPTY_TABLEWARE\",\"imageQuality\":\"CLEAR\",\"foodImage\":false,\"foods\":[],\"sceneSummary\":\"空盘，仅有酱汁痕迹\"}");
        assertThat(vision.recognize(image).isFoodImage()).isFalse();
    }
    @Test void hiddenPackagingCannotBePenalizedAsNonFoodOrScoredAsAGuessedDrink() {
        for(String raw:List.of(
                "{\"sceneType\":\"NON_FOOD\",\"contentAssessment\":\"HIDDEN\",\"imageQuality\":\"CLEAR\",\"foodImage\":false,\"foods\":[]}",
                "{\"sceneType\":\"FOOD\",\"contentAssessment\":\"HIDDEN\",\"imageQuality\":\"CLEAR\",\"foodImage\":true,\"foods\":[{\"name\":\"外带热饮\",\"category\":\"drink\",\"confidence\":0.95,\"identityEvidence\":\"UNKNOWN\"}]}")) {
            respond(raw);
            assertThatThrownBy(()->vision.recognize(image)).isInstanceOfSatisfying(RecognitionFailureException.class,
                    e->assertThat(e.code()).isEqualTo("IMAGE_UNCLEAR"));
        }
    }
    private static final class VisionRecognitionExplanationTestImage {
        static ValidatedImage image() {
            try {
                var pixels=new java.awt.image.BufferedImage(32,24,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=pixels.createGraphics();graphics.setColor(java.awt.Color.GRAY);graphics.fillRect(0,0,32,24);graphics.dispose();
                var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"png",bytes);
                return new ValidatedImage(bytes.toByteArray(),"image/png",".png",32,24,"boundary.png");
            } catch(java.io.IOException e) {throw new IllegalStateException(e);}
        }
    }
}
