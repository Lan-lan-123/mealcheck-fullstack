package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import com.example.mealcheck.dto.assistant.AssistantReference;
import com.example.mealcheck.dto.assistant.AssistantResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserDietProfile;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class LangChainDietAssistantService {
    private static final Logger log = LoggerFactory.getLogger(LangChainDietAssistantService.class);

    private static final int RAG_REFERENCE_LIMIT = 5;
    private static final int RECENT_RECORD_LIMIT = 30;
    private static final int PROMPT_RECORD_LIMIT = 10;
    private static final int RESPONSE_REFERENCE_LIMIT = 3;
    private static final int REFERENCE_SUMMARY_LENGTH = 420;
    private static final int HISTORY_LIMIT = 8;
    private static final String ASSISTANT_HISTORY_PREFIX = "assistant:history:";
    private static final Duration ASSISTANT_HISTORY_TTL = Duration.ofMinutes(30);

    private final AppProperties properties;
    private final UserAccountRepository userRepository;
    private final MealRecordRepository mealRecordRepository;
    private final KnowledgeIndexService knowledgeIndexService;
    private final ObjectMapper objectMapper;
    private final AiStatusService aiStatusService;
    private final RedisCacheService redisCacheService;
    private final UserDietProfileService userDietProfileService;

    public LangChainDietAssistantService(AppProperties properties,
                                         UserAccountRepository userRepository,
                                         MealRecordRepository mealRecordRepository,
                                         KnowledgeIndexService knowledgeIndexService,
                                         ObjectMapper objectMapper,
                                         AiStatusService aiStatusService,
                                         RedisCacheService redisCacheService,
                                         UserDietProfileService userDietProfileService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.knowledgeIndexService = knowledgeIndexService;
        this.objectMapper = objectMapper;
        this.aiStatusService = aiStatusService;
        this.redisCacheService = redisCacheService;
        this.userDietProfileService = userDietProfileService;
    }

    @Transactional
    public AssistantResponse ask(UserPrincipal principal, String question, List<AssistantChatMessage> history) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        List<MealRecord> records = mealRecordRepository.findTop30ByUserOrderByCreatedAtDesc(user)
                .stream()
                .limit(RECENT_RECORD_LIMIT)
                .toList();

        FoodIntent intent = detectIntent(question);
        UserDietProfile profile = userDietProfileService.getOrRefresh(user);
        List<KnowledgeSnippet> snippets = knowledgeIndexService.search(buildRagQuery(question, intent), RAG_REFERENCE_LIMIT);
        knowledgeIndexService.recordHits(snippets.stream().map(KnowledgeSnippet::getId).toList());

        List<AssistantReference> references = toReferences(snippets);
        AssistantResponse fallback = buildLocalResponse(intent, records, references, profile);
        List<AssistantChatMessage> historyContext = mergeHistory(user.getUsername(), history);

        if (!hasRemoteModelConfig()) {
            saveHistory(user.getUsername(), historyContext, question, fallback);
            return fallback;
        }

        try {
            String rawAnswer = buildChatModel().chat(buildPrompt(question, intent, profile, records, snippets, historyContext));
            aiStatusService.recordSuccess("diet-assistant");
            AssistantResponse response = parseModelResponse(rawAnswer, fallback, references);
            saveHistory(user.getUsername(), historyContext, question, response);
            return response;
        } catch (Exception e) {
            aiStatusService.recordFailure("diet-assistant", e.getMessage());
            log.warn("LangChain diet assistant call failed, fallback response will be used: {}", e.getMessage());
            saveHistory(user.getUsername(), historyContext, question, fallback);
            return fallback;
        }
    }

    private List<AssistantChatMessage> mergeHistory(String username, List<AssistantChatMessage> requestHistory) {
        List<AssistantChatMessage> merged = new ArrayList<>();
        redisCacheService.getJson(historyKey(username), new TypeReference<List<AssistantChatMessage>>() {})
                .ifPresent(merged::addAll);
        if (requestHistory != null) {
            merged.addAll(requestHistory);
        }
        return merged.stream()
                .filter(message -> message.getText() != null && !message.getText().isBlank())
                .skip(Math.max(0, merged.size() - HISTORY_LIMIT))
                .toList();
    }

    private void saveHistory(String username,
                             List<AssistantChatMessage> history,
                             String question,
                             AssistantResponse response) {
        List<AssistantChatMessage> next = new ArrayList<>();
        if (history != null) {
            next.addAll(history);
        }
        next.add(new AssistantChatMessage("user", question));
        next.add(new AssistantChatMessage("assistant", response.getAnswer()));
        List<AssistantChatMessage> clipped = next.stream()
                .skip(Math.max(0, next.size() - HISTORY_LIMIT))
                .toList();
        redisCacheService.setJson(historyKey(username), clipped, ASSISTANT_HISTORY_TTL);
    }

    private String historyKey(String username) {
        return ASSISTANT_HISTORY_PREFIX + username;
    }

    private boolean hasRemoteModelConfig() {
        return properties.getAi().getApiKey() != null
                && !properties.getAi().getApiKey().isBlank()
                && properties.getAi().getBaseUrl() != null
                && !properties.getAi().getBaseUrl().isBlank();
    }

    private OpenAiChatModel buildChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(properties.getAi().getApiKey())
                .baseUrl(properties.getAi().getBaseUrl())
                .modelName(properties.getAi().getModel())
                .temperature(0.45)
                .timeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()))
                .build();
    }

    private String buildPrompt(String question,
                               FoodIntent intent,
                               UserDietProfile profile,
                               List<MealRecord> records,
                               List<KnowledgeSnippet> snippets,
                               List<AssistantChatMessage> history) {
        return """
                You are MealCheck's diet assistant. Answer in Chinese.
                The latest user intent is: %s.
                Respond to the exact food, goal, or follow-up in the latest question. Do not reuse a generic template.
                Use recent meal records, conversation history and RAG references. If the question is a follow-up, infer the target from history.
                Return strict JSON only, without markdown fences:
                {
                  "summary": "one concise paragraph tailored to the latest question",
                  "suggestions": ["3 to 5 concrete suggestions tailored to this food or goal"],
                  "riskLevel": "LOW|MEDIUM|HIGH",
                  "weeklyTrend": "brief trend based on recent records"
                }
                Keep it practical and non-medical. For diagnosis or treatment, advise consulting a professional.

                User question:
                %s

                Conversation history:
                %s

                Long-term user diet profile:
                %s

                Recent meal records:
                %s

                RAG references:
                %s
                """.formatted(
                intent.name(),
                question,
                buildHistoryContext(history),
                userDietProfileService.promptText(profile),
                buildMealContext(records),
                buildPromptReferences(snippets)
        );
    }

    private String buildHistoryContext(List<AssistantChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "No previous conversation.";
        }

        int from = Math.max(0, history.size() - HISTORY_LIMIT);
        return history.subList(from, history.size())
                .stream()
                .filter(message -> message.getText() != null && !message.getText().isBlank())
                .map(message -> "- " + sanitizeRole(message.getRole()) + ": " + message.getText().trim())
                .collect(Collectors.joining("\n"));
    }

    private String sanitizeRole(String role) {
        if (role == null) {
            return "user";
        }
        String normalized = role.toLowerCase(Locale.ROOT);
        return ("assistant".equals(normalized) || "user".equals(normalized)) ? normalized : "user";
    }

    private String buildMealContext(List<MealRecord> records) {
        if (records.isEmpty()) {
            return "No meal records yet.";
        }

        return records.stream()
                .limit(PROMPT_RECORD_LIMIT)
                .map(record -> "- " + record.getCreatedAt()
                        + " | goal=" + nullToEmpty(record.getGoal())
                        + " | score=" + record.getScore()
                        + " | foods=" + extractFoodNames(record)
                        + " | risks=" + extractRiskTags(record)
                        + " | summary=" + nullToEmpty(record.getSummary()))
                .collect(Collectors.joining("\n"));
    }

    private String buildPromptReferences(List<KnowledgeSnippet> snippets) {
        if (snippets.isEmpty()) {
            return "No RAG references matched.";
        }

        return snippets.stream()
                .map(snippet -> "- [" + nullToEmpty(snippet.getTitle()) + "] " + summarize(snippet.getContent(), 520))
                .collect(Collectors.joining("\n"));
    }

    private AssistantResponse parseModelResponse(String rawAnswer,
                                                 AssistantResponse fallback,
                                                 List<AssistantReference> references) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return fallback;
        }

        try {
            String json = extractJsonObject(rawAnswer);
            JsonNode node = objectMapper.readTree(json);
            String summary = textOrDefault(node.get("summary"), fallback.getSummary());
            List<String> suggestions = readStringList(node.get("suggestions"));
            if (suggestions.isEmpty()) {
                suggestions = fallback.getSuggestions();
            }
            String riskLevel = normalizeRiskLevel(textOrDefault(node.get("riskLevel"), fallback.getRiskLevel()));
            String weeklyTrend = textOrDefault(node.get("weeklyTrend"), fallback.getWeeklyTrend());
            String answer = composeAnswer(summary, suggestions, weeklyTrend);
            return new AssistantResponse(answer, summary, suggestions, riskLevel, weeklyTrend, references);
        } catch (Exception e) {
            String summary = rawAnswer.trim();
            return new AssistantResponse(summary, summary, fallback.getSuggestions(), fallback.getRiskLevel(), fallback.getWeeklyTrend(), references);
        }
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        });
        return values;
    }

    private AssistantResponse buildLocalResponse(FoodIntent intent,
                                                 List<MealRecord> records,
                                                 List<AssistantReference> references,
                                                 UserDietProfile profile) {
        int averageScore = averageScore(records);
        String riskLevel = deriveRiskLevel(intent, averageScore);
        String weeklyTrend = buildWeeklyTrend(records);
        String summary = buildQuestionSpecificSummary(intent, averageScore, records.isEmpty());
        if (profile != null && profile.getTotalMeals() > 0) {
            summary = summary + " " + profile.getProfileSummary();
        }
        List<String> suggestions = buildLocalSuggestions(intent, averageScore, references);
        String answer = composeAnswer(summary, suggestions, weeklyTrend);
        return new AssistantResponse(answer, summary, suggestions, riskLevel, weeklyTrend, references);
    }

    private String buildRagQuery(String question, FoodIntent intent) {
        return (question == null ? "" : question) + " " + switch (intent) {
            case FRIED -> "油炸 高油 烹饪风险 炸鸡 脂肪控制";
            case BARBECUE -> "烤肉 烧烤 红肉 高油 钠摄入 蔬菜搭配";
            case VEGETABLE -> "蔬菜 膳食纤维 维生素 蛋白质搭配";
            case SWEET_DRINK -> "含糖饮料 甜品 糖摄入 控糖";
            case FAT_LOSS -> "减脂 热量控制 蛋白质 蔬菜 主食";
            case MUSCLE_GAIN -> "增肌 蛋白质 碳水 训练 餐次";
            case GENERAL -> "均衡饮食 主食 蛋白质 蔬菜 高油高糖风险";
        };
    }

    private FoodIntent detectIntent(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);

        if (containsAny(text, "炸鸡", "油炸", "炸串", "薯条", "鸡排", "fried")) {
            return FoodIntent.FRIED;
        }
        if (containsAny(text, "烤肉", "烧烤", "烤串", "烤鱼", "五花肉", "barbecue", "bbq")) {
            return FoodIntent.BARBECUE;
        }
        if (containsAny(text, "蔬菜", "青菜", "沙拉", "西兰花", "绿叶菜", "vegetable")) {
            return FoodIntent.VEGETABLE;
        }
        if (containsAny(text, "奶茶", "饮料", "可乐", "甜品", "蛋糕", "糖", "dessert")) {
            return FoodIntent.SWEET_DRINK;
        }
        if (containsAny(text, "减脂", "减肥", "瘦", "控卡", "fat loss")) {
            return FoodIntent.FAT_LOSS;
        }
        if (containsAny(text, "增肌", "蛋白粉", "练肌肉", "muscle")) {
            return FoodIntent.MUSCLE_GAIN;
        }
        return FoodIntent.GENERAL;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildQuestionSpecificSummary(FoodIntent intent, int averageScore, boolean noRecords) {
        String recordContext = noRecords
                ? "你现在还没有足够的历史饮食记录，所以建议先按这一餐本身来判断。"
                : "结合你最近饮食记录，当前平均评分约为 " + averageScore + "，这次建议重点看这一餐和最近习惯之间的平衡。";

        return switch (intent) {
            case FRIED -> recordContext + "炸鸡或油炸类不是完全不能吃，但油脂密度高，适合控制频率并用清淡配菜来抵消负担。";
            case BARBECUE -> recordContext + "烤肉可以作为偶尔的蛋白质来源，但要注意肥肉、蘸料、盐分和蔬菜不足的问题。";
            case VEGETABLE -> recordContext + "多吃蔬菜是好方向，但如果只吃蔬菜，蛋白质和主食不足会影响饱腹感与恢复。";
            case SWEET_DRINK -> recordContext + "甜饮和甜品主要风险是糖摄入快、饱腹感弱，建议把它们当作偶尔享受而不是日常饮品。";
            case FAT_LOSS -> recordContext + "减脂重点不是极端少吃，而是稳定控制总热量，同时保证蛋白质、蔬菜和适量主食。";
            case MUSCLE_GAIN -> recordContext + "增肌需要稳定蛋白质和训练后的碳水补充，单纯多吃肉或只喝蛋白粉都不够完整。";
            case GENERAL -> recordContext + "食堂选餐可以按“主食 + 优质蛋白 + 蔬菜”的框架来组合，再控制高油高糖频率。";
        };
    }

    private List<String> buildLocalSuggestions(FoodIntent intent, int averageScore, List<AssistantReference> references) {
        List<String> suggestions = new ArrayList<>();

        switch (intent) {
            case FRIED -> {
                suggestions.add("如果想吃炸鸡，优先选小份或和别人分食，不建议再搭配薯条、甜饮或油炸小吃。");
                suggestions.add("同餐加一份绿叶菜、番茄或凉拌菜，主食保持半份到一份即可。");
                suggestions.add("下一餐用蒸、煮、炖类蛋白和蔬菜做平衡，比如鸡蛋、豆腐、鱼肉或清炒青菜。");
            }
            case BARBECUE -> {
                suggestions.add("烤肉优先选瘦牛肉、鸡胸、鱼虾、豆制品，少选五花肉、肥牛和加工肉肠。");
                suggestions.add("蘸料少放，避免重油辣酱和过咸调料，可以搭配生菜、菌菇、海带或黄瓜。");
                suggestions.add("如果这一餐肉较多，主食少量即可，下一餐补蔬菜和清淡蛋白。");
            }
            case VEGETABLE -> {
                suggestions.add("蔬菜建议保留，但同餐最好加鸡蛋、豆腐、鱼肉、鸡肉或牛奶等蛋白质。");
                suggestions.add("如果是减脂餐，也不要完全去掉主食，可以选择半份米饭、玉米、红薯或杂粮饭。");
                suggestions.add("优先选清炒、白灼、凉拌，少选油淋、干锅、地三鲜这类高油做法。");
            }
            case SWEET_DRINK -> {
                suggestions.add("奶茶或甜饮建议选小杯、少糖或无糖，不要和高油主餐一起叠加。");
                suggestions.add("如果只是想喝点有味道的，优先选无糖茶、气泡水、牛奶或酸奶。");
                suggestions.add("当天已经有甜饮时，甜品和含糖零食就尽量跳过。");
            }
            case FAT_LOSS -> {
                suggestions.add("每餐先保证一掌心优质蛋白，再配两拳蔬菜，主食控制在半拳到一拳。");
                suggestions.add("少选油炸、糖醋、红烧、奶茶和甜点，把热量留给更有饱腹感的食物。");
                suggestions.add("不要长期极低碳水，否则容易饿、训练状态差，也更难坚持。");
            }
            case MUSCLE_GAIN -> {
                suggestions.add("每餐保证蛋白质来源，比如鸡蛋、鸡肉、鱼虾、牛肉、豆腐或牛奶。");
                suggestions.add("训练前后保留主食，米饭、面、土豆、玉米都可以帮助恢复。");
                suggestions.add("增肌不是只吃肉，也要有蔬菜和水果，减少肠胃负担。");
            }
            case GENERAL -> {
                suggestions.add("食堂打饭时先确定主食，再选一份蛋白质和至少一份蔬菜。");
                suggestions.add("看到油炸、糖醋、红烧、奶茶时，把它们当作频率管理项，而不是每天标配。");
                suggestions.add("如果上一餐偏油，下一餐就选清蒸、炖煮、凉拌或清炒来拉回平衡。");
            }
        }

        if (averageScore > 0 && averageScore < 75) {
            suggestions.add("最近平均分不算高，建议先连续三餐把蔬菜和蛋白质补齐，再观察评分变化。");
        }

        references.stream()
                .findFirst()
                .map(AssistantReference::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .ifPresent(title -> suggestions.add("本次建议主要参考了知识库片段：" + title + "。"));

        return suggestions;
    }

    private int averageScore(List<MealRecord> records) {
        return records.isEmpty()
                ? 0
                : (int) Math.round(records.stream()
                .map(MealRecord::getScore)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0));
    }

    private String deriveRiskLevel(FoodIntent intent, int averageScore) {
        String base = switch (intent) {
            case FRIED, SWEET_DRINK -> "HIGH";
            case BARBECUE -> "MEDIUM";
            case VEGETABLE, FAT_LOSS, MUSCLE_GAIN, GENERAL -> "LOW";
        };

        if (averageScore > 0 && averageScore < 65 && !"HIGH".equals(base)) {
            return "MEDIUM";
        }
        return base;
    }

    private String normalizeRiskLevel(String riskLevel) {
        String value = riskLevel == null ? "" : riskLevel.trim().toUpperCase(Locale.ROOT);
        if ("HIGH".equals(value) || "MEDIUM".equals(value) || "LOW".equals(value)) {
            return value;
        }
        return "LOW";
    }

    private String buildWeeklyTrend(List<MealRecord> records) {
        List<MealRecord> weeklyRecords = records.stream()
                .filter(record -> record.getCreatedAt() != null)
                .filter(record -> record.getCreatedAt().isAfter(LocalDateTime.now().minusDays(7)))
                .sorted(Comparator.comparing(MealRecord::getCreatedAt))
                .toList();

        if (weeklyRecords.isEmpty()) {
            return "近 7 天还没有可用于趋势判断的饮食记录。";
        }

        int average = averageScore(weeklyRecords);
        long lowScoreCount = weeklyRecords.stream()
                .filter(record -> record.getScore() != null && record.getScore() < 70)
                .count();
        String direction = buildScoreDirection(weeklyRecords);
        return "近 7 天共 " + weeklyRecords.size() + " 条记录，平均评分约 " + average
                + "，低于 70 分的记录 " + lowScoreCount + " 条，" + direction + "。";
    }

    private String buildScoreDirection(List<MealRecord> records) {
        if (records.size() < 2) {
            return "趋势暂不明显";
        }
        Integer first = records.get(0).getScore();
        Integer last = records.get(records.size() - 1).getScore();
        if (first == null || last == null || Math.abs(last - first) < 3) {
            return "整体比较稳定";
        }
        return last > first ? "最近评分有上升迹象" : "最近评分略有下降";
    }

    private String composeAnswer(String summary, List<String> suggestions, String weeklyTrend) {
        StringBuilder answer = new StringBuilder(summary == null ? "" : summary.trim());
        if (weeklyTrend != null && !weeklyTrend.isBlank()) {
            answer.append("\n\n本周趋势：").append(weeklyTrend.trim());
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            answer.append("\n\n建议：");
            for (String suggestion : suggestions) {
                answer.append("\n- ").append(suggestion);
            }
        }
        return answer.toString().trim();
    }

    private String extractFoodNames(MealRecord record) {
        List<FoodItem> foods = Jsons.fromJson(
                objectMapper,
                record.getDetectedFoodsJson(),
                new TypeReference<List<FoodItem>>() {}
        );

        if (foods == null || foods.isEmpty()) {
            return "";
        }

        return foods.stream()
                .map(FoodItem::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String extractRiskTags(MealRecord record) {
        List<String> risks = Jsons.fromJson(
                objectMapper,
                record.getRiskTagsJson(),
                new TypeReference<List<String>>() {}
        );
        if (risks == null || risks.isEmpty()) {
            return "";
        }
        return risks.stream()
                .filter(risk -> risk != null && !risk.isBlank())
                .collect(Collectors.joining(", "));
    }

    private List<AssistantReference> toReferences(List<KnowledgeSnippet> snippets) {
        return snippets.stream()
                .limit(RESPONSE_REFERENCE_LIMIT)
                .map(snippet -> new AssistantReference(
                        snippet.getId(),
                        snippet.getTitle(),
                        summarize(snippet.getContent(), REFERENCE_SUMMARY_LENGTH),
                        snippet.getCategory(),
                        snippet.getScore()
                ))
                .toList();
    }

    private String summarize(String content, int length) {
        if (content == null || content.isBlank()) {
            return "暂无内容";
        }

        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= length) {
            return normalized;
        }
        return normalized.substring(0, length) + "...";
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.asText("").isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return node.asText().trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum FoodIntent {
        FRIED,
        BARBECUE,
        VEGETABLE,
        SWEET_DRINK,
        FAT_LOSS,
        MUSCLE_GAIN,
        GENERAL
    }
}
