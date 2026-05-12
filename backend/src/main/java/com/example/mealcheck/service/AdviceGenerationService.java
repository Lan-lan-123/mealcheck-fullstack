package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.dto.StructureEvaluation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdviceGenerationService {
    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AdviceGenerationService(AppProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String generate(RecognitionResult recognition, StructureEvaluation evaluation, List<KnowledgeSnippet> snippets, String goal) {
        if (properties.getAi().getApiKey() == null || properties.getAi().getApiKey().isBlank()) {
            return localAdvice(recognition, evaluation, snippets, goal);
        }
        try {
            String foods = recognition.getFoods().stream()
                    .map(f -> f.getName() + "(" + f.getCategory() + ")")
                    .collect(Collectors.joining("、"));
            String refs = snippets.stream()
                    .map(s -> "【" + s.getTitle() + "】" + s.getContent())
                    .collect(Collectors.joining("\n"));
            String prompt = """
                    你是饮食结构评估助手。请基于识别结果、评分结果和知识库片段生成建议。
                    严格要求：不要估算热量，不要给医疗诊断，不要输出药物或疾病治疗建议。
                    输出包括：1）本餐结构评价；2）主要风险；3）下一餐可执行建议；4）参考依据。
                    用户目标：%s
                    识别食物：%s
                    饮食评分：%d
                    风险标签：%s
                    知识库片段：
                    %s
                    """.formatted(goal, foods, evaluation.getScore(), String.join("、", evaluation.getRiskTags()), refs);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getAi().getModel());
            body.put("temperature", 0.2);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAi().getBaseUrl()))
                    .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.path("choices").get(0).path("message").path("content").asText();
            }
            return localAdvice(recognition, evaluation, snippets, goal) + "\n\n（模型建议生成失败，已使用本地模板。HTTP " + response.statusCode() + "）";
        } catch (Exception e) {
            return localAdvice(recognition, evaluation, snippets, goal) + "\n\n（模型建议生成失败，已使用本地模板：" + e.getMessage() + "）";
        }
    }

    private String localAdvice(RecognitionResult recognition, StructureEvaluation evaluation, List<KnowledgeSnippet> snippets, String goal) {
        String foods = recognition.getFoods().stream().map(FoodItem::getName).collect(Collectors.joining("、"));
        StringBuilder sb = new StringBuilder();
        sb.append("本餐识别到：").append(foods).append("。\n");
        sb.append("结构评价：").append(evaluation.getSummary()).append("\n");
        if (!evaluation.getRiskTags().isEmpty()) {
            sb.append("主要风险：").append(String.join("、", evaluation.getRiskTags())).append("。\n");
        }
        sb.append("建议：下一餐优先补齐蔬菜和优质蛋白，减少油炸、重油和含糖饮品频率。")
                .append("fat_loss".equals(goal) ? "减脂目标下可选择半份主食、清淡蛋白和一份绿叶菜。" : "")
                .append("muscle_gain".equals(goal) ? "增肌目标下应保证鸡蛋、肉类、鱼虾、豆制品或牛奶等蛋白质来源。" : "")
                .append("light".equals(goal) ? "清淡目标下建议优先选择蒸、煮、炖、白灼类菜品。" : "")
                .append("\n");
        if (!snippets.isEmpty()) {
            sb.append("参考依据：").append(snippets.stream().map(KnowledgeSnippet::getTitle).collect(Collectors.joining("、"))).append("。");
        }
        return sb.toString();
    }
}
