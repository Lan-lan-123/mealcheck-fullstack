package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded visible-component recovery; names, labels and guessed ingredients are insufficient. */
final class FoodStructureRoles {
    private FoodStructureRoles() { }
    private static final Set<String> ROLES=Set.of("staple","protein","vegetable","fruit","dairy");
    private static final Pattern EGG=Pattern.compile("鸡蛋|炒蛋|蛋块|蛋花|蛋碎");
    private static final Pattern UNSURE=Pattern.compile("不可见|未见|看不清|看不到|不见|建议|添加|没有|不含|无蛋|无法|不确定|不能确认|可能|疑似|类似|像是|是否|若|如果|假设|包装|标签|配料表|文字|字样|风味|口味|图案|图片|印有");
    static List<FoodRoleEvidence> evidence(FoodItem food) {
        var result=new ArrayList<FoodRoleEvidence>();String category=FoodClassification.baseCategory(food);
        if(ROLES.contains(category))result.add(new FoodRoleEvidence(category,"CATEGORY",category));
        if(Set.of("vegetable","other").contains(category) && "CONTENT_VISIBLE".equals(food.getIdentityEvidence())
                && food.getConfidence()>=MealScoringService.MIN_CONFIDENCE && food.getNote()!=null) {
            for(String clause:food.getNote().split("[。！？；;，,]")) {
                if(clause.contains("可见") && EGG.matcher(clause).find() && !UNSURE.matcher(clause).find()) {
                    result.add(new FoodRoleEvidence("protein","VISIBLE_COMPONENT",clause.substring(0,Math.min(160,clause.length()))));break;
                }
            }
        }
        return List.copyOf(result);
    }
    static boolean has(FoodItem food,String role) {return evidence(food).stream().anyMatch(e->e.role().equals(role));}
}
