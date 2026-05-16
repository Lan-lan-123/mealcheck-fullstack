package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.dto.StructureEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdviceGenerationService {
    private static final Logger log = LoggerFactory.getLogger(AdviceGenerationService.class);

    private final AiChatClient aiChatClient;

    public AdviceGenerationService(AiChatClient aiChatClient) {
        this.aiChatClient = aiChatClient;
    }

    public String generate(RecognitionResult recognition,
                           StructureEvaluation evaluation,
                           List<KnowledgeSnippet> snippets,
                           String goal) {
        if (!aiChatClient.isConfigured()) {
            return localAdvice(recognition, evaluation, snippets, goal);
        }

        try {
            return aiChatClient.complete("meal-advice", adviceMessages(recognition, evaluation, snippets, goal), 0.2);
        } catch (Exception e) {
            log.warn("Meal advice AI call failed, local advice will be used: {}", e.getMessage());
            return localAdvice(recognition, evaluation, snippets, goal)
                    + "\n\n提示：AI 建议生成失败，当前展示的是本地规则建议。原因：" + e.getMessage();
        }
    }

    private List<Map<String, Object>> adviceMessages(RecognitionResult recognition,
                                                     StructureEvaluation evaluation,
                                                     List<KnowledgeSnippet> snippets,
                                                     String goal) {
        return List.of(Map.of("role", "user", "content", buildPrompt(recognition, evaluation, snippets, goal)));
    }

    private String buildPrompt(RecognitionResult recognition,
                               StructureEvaluation evaluation,
                               List<KnowledgeSnippet> snippets,
                               String goal) {
        String foods = recognition.getFoods().stream()
                .map(food -> food.getName() + "(" + food.getCategory() + ")")
                .collect(Collectors.joining("、"));
        String refs = snippets.stream()
                .map(snippet -> "【" + snippet.getTitle() + "】" + snippet.getContent())
                .collect(Collectors.joining("\n"));

        return """
                你是 MealCheck 的饮食建议助手。请根据识别食物、结构评分、风险标签和 RAG 知识，生成中文饮食建议。
                输出 2 到 4 句话即可，风格具体、温和、可执行，不要输出 JSON。

                用户目标：%s
                识别食物：%s
                饮食评分：%d
                风险标签：%s

                参考知识：
                %s
                """.formatted(
                goal,
                foods,
                evaluation.getScore(),
                String.join("、", evaluation.getRiskTags()),
                refs
        );
    }

    private String localAdvice(RecognitionResult recognition,
                               StructureEvaluation evaluation,
                               List<KnowledgeSnippet> snippets,
                               String goal) {
        String foods = recognition.getFoods().stream()
                .map(FoodItem::getName)
                .collect(Collectors.joining("、"));

        StringBuilder advice = new StringBuilder();
        advice.append("本餐识别到：").append(foods).append("。\n");
        advice.append("结构评价：").append(evaluation.getSummary()).append("\n");
        if (!evaluation.getRiskTags().isEmpty()) {
            advice.append("需要关注：").append(String.join("、", evaluation.getRiskTags())).append("。\n");
        }
        advice.append("建议：保持主食、优质蛋白和蔬菜的搭配，减少高油、高糖或重口味食物的频率。");

        if ("fat_loss".equals(goal)) {
            advice.append(" 减脂目标下可以控制主食份量，但不要完全省略蛋白质和蔬菜。");
        } else if ("muscle_gain".equals(goal)) {
            advice.append(" 增肌目标下建议补足蛋白质，并保留适量主食帮助训练恢复。");
        } else if ("light".equals(goal)) {
            advice.append(" 清淡目标下建议优先选择蒸、煮、炖、凉拌等做法。");
        }

        if (!snippets.isEmpty()) {
            advice.append("\n参考依据：")
                    .append(snippets.stream().map(KnowledgeSnippet::getTitle).collect(Collectors.joining("、")))
                    .append("。");
        }
        return advice.toString();
    }
}
