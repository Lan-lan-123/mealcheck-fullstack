package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.RecognitionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed evaluation criteria, never sent to the recognition model. Not production recognition logic. */
final class VisionEdgeCriteria {
    private static final Pattern MEASURED=Pattern.compile("(?:[0-9]+(?:\\.[0-9]+)?|[一二三四五六七八九十百]+)\\s*(?:毫克|毫升|千卡|大卡|克|kcal|mg|ml|g)",Pattern.CASE_INSENSITIVE);
    private static final Pattern INGREDIENT=Pattern.compile("无糖|低糖|含糖|全脂|脱脂|牛奶|豆浆|咖啡|奶茶|果汁|可乐");
    private static final Pattern SPECIFIC=Pattern.compile("牛奶|豆浆|果汁|咖啡|奶茶|米饭|鸡肉|猪肉|饼干|面包|坚果|麦片|鸡蛋|酸奶|可乐");
    private VisionEdgeCriteria() { }
    static List<String> issues(JsonNode expected,String decision,RecognitionResult result,List<FoodItem> scored,JsonNode raw) {
        var issues=new ArrayList<String>();
        boolean allowed=false;for(var value:expected.path("outcomes"))allowed |= value.asText().equals(decision);
        if(!allowed)issues.add("UNEXPECTED_DECISION:"+decision);
        if("FOOD".equals(decision)) {
            if(scored.isEmpty())issues.add("VISIBLE_FOOD_NOT_SCORED");
            if(result!=null)for(var food:result.getFoods()) {
                boolean named=false;for(var name:expected.path("names"))named |= food.getName().contains(name.asText());
                if(!named)issues.add("UNSUPPORTED_FOOD_IDENTITY:"+food.getName());
                if(expected.has("category") && !expected.path("category").asText().equals(food.getCategory()))issues.add("WRONG_FOOD_CATEGORY");
            }
        }
        if(!"FOOD".equals(decision) && !scored.isEmpty())issues.add("UNASSESSABLE_OR_NON_FOOD_WAS_SCORED");
        if(expected.path("requireUnknownIdentity").asBoolean()) {
            for(var food:raw.path("foods")) {
                String name=food.path("name").asText();
                if(SPECIFIC.matcher(name).find())issues.add("GUESSED_HIDDEN_IDENTITY:"+name);
                if(food.path("confidence").asDouble(1)>=MealScoringService.MIN_CONFIDENCE)issues.add("HIDDEN_IDENTITY_CONFIDENT");
            }
        }
        var texts=new ArrayList<String>();texts.add(raw.path("sceneSummary").asText());texts.add(raw.path("rejectionReason").asText());
        for(var food:raw.path("foods")) {texts.add(food.path("name").asText());texts.add(food.path("note").asText());}
        for(String text:texts) {
            if(expected.path("forbidMeasuredIntake").asBoolean() && affirmative(MEASURED,text))issues.add("INVENTED_MEASURED_INTAKE");
            if(expected.path("forbidIngredients").asBoolean() && affirmative(INGREDIENT,text))issues.add("GUESSED_OPAQUE_DRINK_INGREDIENTS");
        }
        return List.copyOf(new LinkedHashSet<>(issues));
    }
    private static boolean affirmative(Pattern pattern,String text) {
        var matcher=pattern.matcher(text);
        while(matcher.find()) {
            String prefix=text.substring(Math.max(0,matcher.start()-14),matcher.start()).replaceFirst("(?s)^.*[，,。；;\\n]","");
            if(!prefix.matches(".*(?:无法|不能|未知|未确认|不确定|不清楚|是否|不明).{0,10}$"))return true;
        }
        return false;
    }
}
