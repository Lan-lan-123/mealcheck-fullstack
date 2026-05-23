package com.example.mealcheck.service;

import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
            throw new AiChatClient.AiClientException("视觉识别服务未配置 AI API Key、Base URL 或模型。");
        }

        try {
            String mime = image.getContentType() == null ? "image/jpeg" : image.getContentType();
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image.getBytes());
            String content = aiChatClient.complete("vision-recognition", visionMessages(dataUrl), 0.1);

            RecognitionResult result = objectMapper.readValue(Jsons.extractJsonObject(content), RecognitionResult.class);
            if (!result.isFoodImage()) {
                result.setDemoMode(false);
                return result;
            }

            if (result.getFoods() == null || result.getFoods().isEmpty()) {
                String message = "视觉模型没有返回有效 foods 字段。";
                log.warn("{} content={}", message, content);
                throw new AiChatClient.AiClientException(message);
            }

            result.setDemoMode(false);
            return result;
        } catch (AiChatClient.AiClientException e) {
            throw e;
        } catch (Exception e) {
            String message = "视觉模型调用失败：" + e.getMessage();
            log.warn(message, e);
            throw new AiChatClient.AiClientException(message, e);
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
                You are MealCheck's food-image recognition assistant.
                Determine whether the uploaded image is a meal or food-related image before identifying foods.
                Return JSON only, without Markdown:
                {
                  "foodImage": true,
                  "rejectionReason": "",
                  "sceneSummary": "short summary of the visible meal",
                  "foods": [
                    {
                      "name": "food name",
                      "category": "staple|protein|vegetable|fruit|dairy|soup|drink|dessert|fried|oily|other",
                      "confidence": 0.0,
                      "note": "short visible cue"
                    }
                  ]
                }

                Rules:
                1. If the image is not mainly about edible food, a meal tray, tableware containing food, packaged food, or drinks,
                   return "foodImage": false, set "foods": [], and explain briefly in "rejectionReason" in Chinese.
                2. For non-food images such as people, scenery, pets, documents, screens, empty tables, or unrelated objects,
                   do not guess any dishes.
                3. If it is a food image, return "foodImage": true, keep "rejectionReason" empty, and identify only foods that are visibly present.
                4. confidence must be between 0 and 1.
                """;
    }

}
