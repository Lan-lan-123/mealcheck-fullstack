package com.example.mealcheck.dto;

public record MealAnalysisRequestResponse(String key, String status, MealAnalysisResponse result, int retryAfterSeconds,
        String errorCode, String stage, String message) {
    public MealAnalysisRequestResponse(String key,String status,MealAnalysisResponse result,int retryAfterSeconds,String errorCode) {
        this(key,status,result,retryAfterSeconds,errorCode,stageFor(status),messageFor(status));
    }
    public MealAnalysisRequestResponse(String key,String status,MealAnalysisResponse result,int retryAfterSeconds) {
        this(key,status,result,retryAfterSeconds,null);
    }
    private static String stageFor(String status) {
        return switch (status) {
            case "PENDING" -> "QUEUED";
            case "PROCESSING" -> "PROCESSING";
            case "SUCCEEDED" -> "COMPLETED";
            default -> "FAILED";
        };
    }
    private static String messageFor(String status) {
        return switch (status) {
            case "PENDING" -> "图片已接收，正在排队；高峰期可能需要几分钟，请勿重复提交。";
            case "PROCESSING" -> "正在识别食物、计算评分并生成饮食建议。";
            case "SUCCEEDED" -> "餐食分析已完成。";
            default -> "请查看任务错误说明。";
        };
    }
}
