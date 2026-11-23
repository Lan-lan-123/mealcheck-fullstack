package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Ensure the evaluator cannot award safe abstention to a confidently invented hidden food. */
class VisionEdgeCriteriaTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode expected(String decision,boolean unknown) throws Exception {
        return json.readTree("{\"outcomes\":[\""+decision+"\"],\"names\":[\"米饭\"],\"category\":\"staple\",\"requireUnknownIdentity\":"+unknown+",\"forbidMeasuredIntake\":true}");
    }
    List<String> check(String decision,String name,double confidence,boolean unknown,String note) throws Exception {
        var r=new RecognitionResult();r.setFoodImage(true);r.setFoods(List.of(new FoodItem(name,"staple",confidence,note)));
        var raw=json.createObjectNode();raw.putArray("foods").addObject().put("name",name).put("confidence",confidence).put("note",note);
        return VisionEdgeCriteria.issues(expected(decision,unknown),decision,r,new MealScoringService().acceptedFoods(r),raw);
    }
    @Test void knownVisibleFoodMustBeRetained(){
        assertThatCode(()->assertThat(check("FOOD","米饭",.9,false,"少量米饭")).isEmpty()).doesNotThrowAnyException();
    }
    @Test void uncertainCandidateCannotPassWithAnInventedSpecificIdentity() throws Exception {
        assertThat(check("UNCERTAIN_ONLY","牛奶",.2,true,"无法确认")).anyMatch(x->x.startsWith("GUESSED_HIDDEN_IDENTITY"));
    }
    @Test void confidentlyGenericCandidateIsStillWrongForHiddenContents() throws Exception {
        assertThat(check("UNCERTAIN_ONLY","未确认食物",.9,true,"无法确认")).contains("HIDDEN_IDENTITY_CONFIDENT","UNASSESSABLE_OR_NON_FOOD_WAS_SCORED");
    }
    @Test void genericLowConfidenceCandidateDoesNotEnterScoring() throws Exception {
        assertThat(check("UNCERTAIN_ONLY","未确认食物",.2,true,"内容被遮挡")).isEmpty();
    }
    @Test void inferredGramsAreNotAcceptedAsVisibleFoodIdentity() throws Exception {
        assertThat(check("FOOD","米饭",.9,false,"估计100克")).contains("INVENTED_MEASURED_INTAKE");
        assertThat(check("FOOD","米饭",.9,false,"无法从照片确定100克的实际份量")).doesNotContain("INVENTED_MEASURED_INTAKE");
    }
    @Test void opaqueDrinkIngredientsNeedAffirmativeEvidence() throws Exception {
        var e=(com.fasterxml.jackson.databind.node.ObjectNode)expected("IMAGE_UNCLEAR",true);e.put("forbidIngredients",true);
        assertThat(VisionEdgeCriteria.issues(e,"IMAGE_UNCLEAR",null,List.of(),json.readTree("{\"sceneSummary\":\"无法判断是否含糖\",\"foods\":[]}"))).isEmpty();
        assertThat(VisionEdgeCriteria.issues(e,"IMAGE_UNCLEAR",null,List.of(),json.readTree("{\"sceneSummary\":\"这杯是无糖牛奶\",\"foods\":[]}"))).contains("GUESSED_OPAQUE_DRINK_INGREDIENTS");
    }
    @Test void declaringAnOccludedContainerEmptyFailsTheDecisionCriterion() throws Exception {
        assertThat(VisionEdgeCriteria.issues(expected("IMAGE_UNCLEAR",false),"NON_FOOD",null,List.of(),json.readTree("{\"foods\":[]}"))).contains("UNEXPECTED_DECISION:NON_FOOD");
    }
}
