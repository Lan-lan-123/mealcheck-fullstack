package com.example.mealcheck.service;

import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public String lastModel() { return aiChatClient.lastModel(); }

    public RecognitionResult recognize(ValidatedImage image) {
        ImageQualityGuard.rejectNearBlack(image);
        if (!aiChatClient.isConfigured()) {
            throw new AiChatClient.AiClientException("视觉识别服务未配置 AI API Key、Base URL 或模型。");
        }

        try {
            String dataUrl = "data:" + image.contentType() + ";base64,"
                    + image.base64();
            String content = aiChatClient.complete("vision-recognition", visionMessages(dataUrl), 0.1);

            var payload = objectMapper.readTree(Jsons.extractJsonObject(content));
            RecognitionResult result = objectMapper.treeToValue(payload, RecognitionResult.class);
            // Candidates supplied by a model are derived locally, never accepted as explanatory metadata.
            result.setUncertainFoods(List.of());
            // Hidden contents are unknown, even when the model labels the container NON_FOOD.
            if ("HIDDEN".equals(payload.path("contentAssessment").asText())
                    && result.getFoods() != null && result.getFoods().isEmpty())
                throw RecognitionFailureException.unclear();
            if ("UNCLEAR".equals(result.getImageQuality()) || "UNCLEAR".equals(payload.path("sceneType").asText()))
                throw RecognitionFailureException.unclear();
            if (!"CLEAR".equals(result.getImageQuality())) throw new AiChatClient.InvalidResponseException("视觉模型返回无效图片清晰度状态。");
            if (payload.has("foodImage") && !payload.path("foodImage").isBoolean())
                throw RecognitionFailureException.inconsistent();
            if (result.getFoods() == null || !payload.path("foods").isArray())
                throw RecognitionFailureException.inconsistent();
            if (payload.has("sceneType")) {
                String scene = payload.path("sceneType").asText();
                if (List.of("EMPTY_TABLEWARE", "NON_FOOD").contains(scene)) {
                    if (!result.getFoods().isEmpty()) throw RecognitionFailureException.inconsistent();
                    // Explicit scene evidence, not an empty array alone, establishes non-food.
                    result.setFoodImage(false);
                    if ("EMPTY_TABLEWARE".equals(scene))
                        result.setRejectionReason("图片中是空餐具，未发现可记录的食物或饮品。");
                } else if (!"FOOD".equals(scene) || !result.isFoodImage()) {
                    throw RecognitionFailureException.inconsistent();
                }
            }
            if (payload.has("sceneType") && (!payload.path("imageQuality").isTextual() || !payload.path("foodImage").isBoolean()))
                throw new AiChatClient.InvalidResponseException("视觉模型场景结果缺少清晰度或餐食标记。");
            if (!result.isFoodImage()) {
                if (!result.getFoods().isEmpty()) throw RecognitionFailureException.inconsistent();
                result.setDemoMode(false);
                return result;
            }

            if (result.getFoods() == null || result.getFoods().isEmpty()) {
                log.warn("Vision response missing food items");
                throw RecognitionFailureException.inconsistent();
            }
            for (var candidate : payload.path("foods")) {
                if (!candidate.path("confidence").isNumber()) throw new AiChatClient.InvalidResponseException("视觉模型缺少有效置信度。");
                var attributes = candidate.path("attributes");
                if (!attributes.isMissingNode() && !attributes.isNull()) {
                    if (!attributes.isObject()) throw new AiChatClient.InvalidResponseException("视觉模型返回无效风险属性。");
                    for (String key : List.of("fried", "highOil")) {
                        var value = attributes.path(key);
                        if (!value.isMissingNode() && !value.isNull() && !value.isBoolean())
                            throw new AiChatClient.InvalidResponseException("风险属性须为布尔值或未知。");
                    }
                }
            }
            if (result.getFoods().stream().anyMatch(food -> food == null || food.getName() == null
                    || food.getName().isBlank() || food.getCategory() == null || food.getCategory().isBlank()
                    || !FoodClassification.isSupportedCategory(food.getCategory())
                    || !Double.isFinite(food.getConfidence()) || food.getConfidence() < 0 || food.getConfidence() > 1)) {
                throw new AiChatClient.InvalidResponseException("视觉模型返回无效食物字段。");
            }

            for (var food : result.getFoods()) {
                String basis=food.getIdentityEvidence();
                if (basis != null && !List.of("CONTENT_VISIBLE","LABEL_READABLE","UNKNOWN").contains(basis))
                    throw new AiChatClient.InvalidResponseException("视觉模型返回无效识别依据。");
                String cue=java.util.Objects.toString(food.getNote(),"");
                boolean hidden=cue.matches("(?s).*(?:内容无法辨认|无法确认内容|看不到内部|看不到内容|无可见内容|仅凭包装|典型热饮包装|标签不可读|标签无法辨认).*" );
                if ("HIDDEN".equals(payload.path("contentAssessment").asText()) && !"LABEL_READABLE".equals(basis)
                        || hidden && !"LABEL_READABLE".equals(basis))
                    food.setIdentityEvidence("UNKNOWN");
            }
            result.setFoods(result.getFoods().stream().map(RecognitionSemanticValidator::review).toList());
            if (result.getFoods().stream().allMatch(f->"UNKNOWN".equals(f.getIdentityEvidence())))
                throw RecognitionFailureException.unclear();
            result.setDemoMode(false);
            return result;
        } catch (RecognitionFailureException e) {
            throw e;
        } catch (AiBudgetService.BudgetExceededException | AiBudgetService.BudgetUnavailableException
                 | RemoteCallGuard.RemoteCallDeadlineException e) {
            throw e;
        } catch (AiChatClient.AiClientException e) {
            throw e;
        } catch (Exception e) {
            String message = "视觉模型返回结果无效，请稍后重试。";
            log.warn("Vision response failed type={}", e.getClass().getSimpleName());
            throw new AiChatClient.InvalidResponseException(message, e);
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
                  "imageQuality": "CLEAR",
                  "sceneType": "FOOD",
                  "contentAssessment": "VISIBLE",
                  "rejectionReason": "",
                  "sceneSummary": "short summary of the visible meal",
                  "foods": [
                    {
                      "name": "food name",
                      "category": "staple|protein|vegetable|fruit|dairy|soup|drink|dessert|other",
                      "attributes": {"fried": null, "highOil": null},
                      "confidence": 0.0,
                      "identityEvidence": "CONTENT_VISIBLE",
                      "note": "short visible cue"
                    }
                  ]
                }

                Rules:
                Report contentAssessment as VISIBLE, LABEL_READABLE, HIDDEN, EMPTY_CONFIRMED or NOT_APPLICABLE.
                HIDDEN means contents cannot be seen and no readable label identifies them. Container shape never establishes contents.
                An opaque unlabelled sealed bag or closed opaque cup with unreadable labels MUST be UNCLEAR, not NON_FOOD or FOOD.
                Do not call a closed cup "外带热饮", coffee or tea merely from its shape. A clear photograph can still have HIDDEN contents.
                An empty container requires visible evidence of emptiness; not seeing inside is not that evidence.
                Each food must state identityEvidence: CONTENT_VISIBLE (edible contents identifiable), LABEL_READABLE
                (quote the readable food label in note), or UNKNOWN (identity not established, never eligible for scoring).
                For mixed scenes retain visible foods, mark hidden candidates UNKNOWN; if all contents are hidden return UNCLEAR.
                Choose exactly one mutually exclusive outcome, checking assessability FIRST:
                1. UNASSESSABLE: blur, darkness or obstruction prevents deciding what the image contains.
                   Return exactly this field combination, with a brief Chinese reason:
                   {"foodImage":true,"imageQuality":"UNCLEAR","rejectionReason":"图片细节不足，请重新拍摄",
                    "sceneType":"UNCLEAR","sceneSummary":"","foods":[]}
                   Here foodImage=true is only a compatibility sentinel; it does NOT assert that food is visible.
                   These rules take precedence over the other outcomes. Do not guess foods or classify this as non-food.
                2. CLEAR NON-FOOD: the scene is assessable but contains no edible food or drinks, including
                   people, scenery, pets, documents, screens, empty tables and empty tableware.
                   Set foodImage=false, imageQuality="CLEAR", foods=[], rejectionReason to a brief Chinese reason,
                   and sceneSummary to a brief Chinese description of visible objects.
                   For empty plates, bowls, trays or other empty tableware set sceneType="EMPTY_TABLEWARE".
                   Other clear non-food scenes use sceneType="NON_FOOD". Tableware alone is not edible food.
                3. CLEAR FOOD: edible food or drinks are visible. Set foodImage=true, imageQuality="CLEAR",
                   rejectionReason="", and identify only visibly present foods.
                   Set sceneType="FOOD" and return at least one visible food or drink.
                   A clear food result with foods=[] is contradictory; do not use it for an empty tray.
                Small visible leftovers are FOOD even when most of the plate is empty. Smears or stains alone are not a food portion.
                A spoon, lid or hand covering the contents does not establish empty tableware. If no edible contents can be assessed,
                use UNCLEAR; if some foods are visible, report those only and lower confidence for obscured identities.
                Drinks count as FOOD. For an opaque cup or bottle, do not invent milk, soy, sugar content or ingredients from container shape.
                Sealed packaged foods can be food-related: use readable labels or visible contents for identity; unreadable packaging is not proof of contents.
                With multiple plates, identify visibly distinct foods across the scene without inventing servings, grams or which person ate them.
                For outcome 3, write food names, sceneSummary and notes in Chinese; keep category codes in English.
                confidence must be a number between 0 and 1. It is an uncertainty estimate, not a calibrated probability.
                Lower confidence when a food name or category is uncertain and describe the visible cue in note.
                category describes the BASE FOOD GROUP, never a cooking method or oil level.
                Fried chicken is protein with attributes.fried=true; French fries are staple with attributes.fried=true.
                Pork belly can be protein and separately have highOil risk; do not replace protein with oily.
                attributes.fried and attributes.highOil are independent estimates: true, false, or null (unknown).
                Use null when not assessable; unknown does not mean false. High oil is a visible risk estimate, not measured oil or fat intake.
                Do not assume every braised dish is high oil from its name alone. Mention concrete visible cues in note.
                A food can be non-fried yet have highOil risk, or fried with uncertain oil level.
                Here fried means deep-fried in oil (油炸), NOT the broad English sense of frying.
                Stir-fried (炒、清炒、番茄炒蛋) and pan-fried/seared (煎) do not by themselves establish deep-frying.
                When the method is clearly only stir-fried or pan-fried, set fried=false; if unclear or mixed, use null.
                Never infer highOil=false from fried=false, or highOil=true just because a dish is stir-fried or pan-fried.
                fried=true requires specific evidence of deep-frying; describe the method evidence in note.
                Follow this application's food-group convention: whole corn/corn kernels (玉米、玉米棒、玉米粒),
                rice, noodles, potatoes and sweet potatoes are staple, not vegetable. Corn salad or mixed dishes
                must be classified from their visible ingredients; corn juice is drink. Do not force every name containing corn into staple.
                """;
    }

}
