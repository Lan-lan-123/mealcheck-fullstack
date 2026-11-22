package com.example.mealcheck.service;

import java.util.Locale;

/** A topic title takes priority over incidental examples or uncertainty wording in a chunk. */
final class KnowledgeCategoryClassifier {
    private KnowledgeCategoryClassifier() { }

    static String infer(String title, String content) {
        String primary = match(title);
        return "general".equals(primary) ? match(content) : primary;
    }

    private static String match(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (contains(text, "减脂", "减肥", "控卡", "热量", "fat")) return "fat_loss";
        if (contains(text, "增肌", "蛋白", "肌肉", "训练", "muscle")) return "muscle_gain";
        if (contains(text, "油炸", "高油", "炸鸡", "烧烤", "烤肉", "红烧")) return "high_oil";
        if (contains(text, "甜", "含糖", "奶茶", "饮料", "糖")) return "sugar";
        if (contains(text, "蔬菜", "纤维", "绿叶菜")) return "vegetable";
        if (contains(text, "主食", "米饭", "面", "碳水", "红薯")) return "staple";
        return "general";
    }

    private static boolean contains(String text, String... keywords) {
        for (String keyword : keywords) if (text.contains(keyword)) return true;
        return false;
    }
}
