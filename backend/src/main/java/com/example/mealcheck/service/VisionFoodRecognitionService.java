package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class VisionFoodRecognitionService {
    private static final Logger log = LoggerFactory.getLogger(VisionFoodRecognitionService.class);

    private final AiChatClient aiChatClient;
    private final ObjectMapper objectMapper;

    public VisionFoodRecognitionService(AiChatClient aiChatClient, ObjectMapper objectMapper) {
        this.aiChatClient = aiChatClient;
        this.objectMapper = objectMapper;
    }

    public RecognitionResult recognize(MultipartFile image) {
        if (!aiChatClient.isConfigured()) {
            log.warn("Vision recognition fallback: AI is not fully configured.");
            return demoResult("未配置 AI API Key、Base URL 或模型，已使用演示识别结果。");
        }

        try {
            String mime = image.getContentType() == null ? "image/jpeg" : image.getContentType();
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image.getBytes());
            String content = aiChatClient.complete("vision-recognition", visionMessages(dataUrl), 0.1);

            RecognitionResult result = objectMapper.readValue(Jsons.extractJsonObject(content), RecognitionResult.class);
            if (result.getFoods() == null || result.getFoods().isEmpty()) {
                String message = "视觉模型没有返回 foods 字段，已使用演示识别结果。";
                log.warn("{} content={}", message, content);
                return demoResult(message);
            }

            result.setDemoMode(false);
            return result;
        } catch (Exception e) {
            String message = "视觉模型调用失败：" + e.getMessage();
            log.warn(message, e);
            return demoResult(message);
        }
    }

    private List<Map<String, Object>> visionMessages(String dataUrl) {
        return List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", visionPrompt()),
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                )
        ));
    }

    private String visionPrompt() {
        return """
                你是 MealCheck 的食物图片识别助手。请识别图片中的所有可见食物，并只返回 JSON，不要 Markdown。

                返回格式：
                {
                  "sceneSummary": "一句话概括这张餐食图片",
                  "foods": [
                    {
                      "name": "食物名称",
                      "category": "staple|protein|vegetable|fruit|dairy|soup|drink|dessert|fried|oily|other",
                      "confidence": 0.0,
                      "note": "简短说明"
                    }
                  ]
                }

                规则：
                1. 能识别具体食物就写具体名称，例如米饭、鸡腿、炒青菜、鸡蛋汤。
                2. 不确定时可以用较宽泛名称，例如肉类、绿叶蔬菜、汤品。
                3. confidence 使用 0 到 1 之间的小数。
                4. 如果有饮料、甜品、油炸或高油菜品，也要列出。
                """;
    }

    private RecognitionResult demoResult(String reason) {
        RecognitionResult result = new RecognitionResult();
        result.setDemoMode(true);
        result.setSceneSummary(reason);

        List<FoodItem> foods = new ArrayList<>();
        foods.add(new FoodItem("米饭", "staple", 0.82, "演示数据"));
        foods.add(new FoodItem("鸡腿", "protein", 0.78, "演示数据"));
        foods.add(new FoodItem("炒青菜", "vegetable", 0.74, "演示数据"));
        foods.add(new FoodItem("鸡蛋汤", "soup", 0.69, "演示数据"));
        result.setFoods(foods);
        return result;
    }
}
