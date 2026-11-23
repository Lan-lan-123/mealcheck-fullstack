package com.example.mealcheck.service;

final class AssistantAnswerFormatting {
    private AssistantAnswerFormatting() { }
    static boolean wantsTrend(String question) {
        return question!=null && (question.contains("趋势") || question.contains("周报") || question.contains("本周")
                || question.contains("这周") || question.toLowerCase(java.util.Locale.ROOT).contains("weekly report"));
    }
    static String trend(String value) {
        if(value==null)return "";
        String text=value.strip();
        if(text.isEmpty() || text.equalsIgnoreCase("null") || text.matches("(?is).*(insufficient|no .*record|记录不足|无.*记录|暂无.*记录|无法.*趋势).*") )return "";
        return text;
    }
}
