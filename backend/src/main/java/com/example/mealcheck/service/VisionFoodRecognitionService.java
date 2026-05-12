package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VisionFoodRecognitionService {
    private final AppProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VisionFoodRecognitionService(AppProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public RecognitionResult recognize(MultipartFile image) {
        if (properties.getAi().getApiKey() == null || properties.getAi().getApiKey().isBlank()) {
            return demoResult();
        }
        try {
            String mime = image.getContentType() == null ? "image/jpeg" : image.getContentType();
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image.getBytes());
            String prompt = """
            你是饮食结构识别助手。请认真观察整张图片，从左到右、从上到下逐项识别餐盘、碗、托盘中的所有可见食物。
            你的任务是识别饮食结构，不要估算热量，不要估算重量，不要给医学建议。
            
            特别注意：
            1. 不要漏掉肉类、鸡腿、鸡翅、鱼、牛肉、猪肉、虾、鸡蛋、豆腐等蛋白质来源。
            2. 如果看到鸡腿、鸡肉、肉排、鱼、虾、蛋类、豆制品，category 必须优先标为 protein。
            3. 如果某个蛋白质食物看起来比较油，可以在 note 中说明“可能有一定油脂”或“油炸烹饪，存在高油风险”，但 category 仍然用 protein。
            4. 如果看到米饭、面条、馒头、面包、土豆、包子、粥类等，category 标为 staple。
            5. 如果看到青菜、绿叶菜、菌菇、番茄、胡萝卜、黄瓜等，category 标为 vegetable。
            6. 如果看到汤、蛋花汤、紫菜汤、粥汤类液体食品等，category 标为 soup。
            7. 请尽量列出所有明显可见食物，不要只列出一部分。
            
            关于饮品识别，请重点遵守以下规则：
            8. 如果图片中出现杯装、瓶装、罐装饮品，即使没有文字标签，也必须作为一个食物项列出，category 标为 drink。
            9. 如果饮品是白色或浅米色、装在纸杯中，并且餐食场景明显是中式早餐（如包子、粥、鸡蛋等），优先判断为“豆浆”或“热豆浆”。
            10. 如果饮品呈乳白色且更像牛奶，可写“牛奶”；如果无法确定是豆浆还是牛奶，优先写“豆浆或牛奶”，并在 note 中说明“更可能为豆浆”或“需结合早餐场景判断”。
            11. 如果看到黑色罐装饮料、可乐罐、汽水罐、碳酸饮料，请在 name 中写“可乐或罐装含糖饮料”，category 标为 drink，note 中说明“可能为含糖饮料”。
            12. 如果是白水、矿泉水、无糖茶、黑咖啡等，不要标为高风险饮料。
            13. 如果是豆浆，note 中应写“早餐常见饮品，可提供一定植物蛋白”；如果明显是加糖甜豆浆，可补充“可能含糖”。
            
            关于饮品风险判断：
            14. 不要因为看见纸杯就默认是含糖饮料。
            15. 在高校早餐场景中，如果是白色/米色热饮且与包子、粥、鸡蛋同时出现，应优先考虑豆浆，而不是奶茶或甜饮。
            16. 只有在明显像可乐、奶茶、果汁、汽水、含糖乳饮料时，才在 note 中写“可能为含糖饮料”。
            
            只输出 JSON，不要输出 Markdown，不要添加解释文字。
            JSON 格式如下：
            {"sceneSummary":"一句话概括餐盘内容","foods":[{"name":"豆浆","category":"drink","confidence":0.9,"note":"早餐常见饮品，可提供一定植物蛋白"}]}
            
            category 只能从以下枚举中选择：
            staple, protein, vegetable, fruit, dairy, soup, drink, dessert, fried, oily, other。
            """;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getAi().getModel());
            body.put("temperature", 0.1);
            body.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "text", "text", prompt),
                            Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                    )
            )));
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAi().getBaseUrl()))
                    .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("视觉模型调用失败: HTTP " + response.statusCode() + " - " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            String cleanJson = Jsons.extractJsonObject(content);
            RecognitionResult result = objectMapper.readValue(cleanJson, RecognitionResult.class);
            result.setDemoMode(false);
            if (result.getFoods() == null || result.getFoods().isEmpty()) {
                return demoResult();
            }
            return result;
        } catch (Exception e) {
            RecognitionResult fallback = demoResult();
            fallback.setSceneSummary("视觉模型调用失败，已进入演示识别模式。原因：" + e.getMessage());
            fallback.setDemoMode(true);
            return fallback;
        }
    }

    private RecognitionResult demoResult() {
        RecognitionResult result = new RecognitionResult();
        result.setDemoMode(true);
        result.setSceneSummary("演示模式：餐盘可能包含米饭、鸡腿、炒青菜和鸡蛋汤。配置 API Key 后将调用真实 Qwen2.5-VL。 ");
        List<FoodItem> foods = new ArrayList<>();
        foods.add(new FoodItem("米饭", "staple", 0.82, "主食"));
        foods.add(new FoodItem("鸡腿", "protein", 0.78, "蛋白质来源，可能有一定油脂"));
        foods.add(new FoodItem("炒青菜", "vegetable", 0.74, "蔬菜"));
        foods.add(new FoodItem("鸡蛋汤", "soup", 0.69, "汤品"));
        result.setFoods(foods);
        return result;
    }
}
