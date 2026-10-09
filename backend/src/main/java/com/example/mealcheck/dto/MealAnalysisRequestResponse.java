package com.example.mealcheck.dto;

public record MealAnalysisRequestResponse(String key, String status, MealAnalysisResponse result, int retryAfterSeconds, String errorCode) {
    public MealAnalysisRequestResponse(String key,String status,MealAnalysisResponse result,int retryAfterSeconds) {
        this(key,status,result,retryAfterSeconds,null);
    }
}
