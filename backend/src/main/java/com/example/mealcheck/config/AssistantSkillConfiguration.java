package com.example.mealcheck.config;

import com.example.mealcheck.service.skill.AssistantSkill;
import com.example.mealcheck.service.skill.AssistantSkillPlan;
import com.example.mealcheck.service.skill.KeywordAssistantSkill;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AssistantSkillConfiguration {

    @Bean
    AssistantSkill weeklyReportSkill() {
        return skill("weekly-report", "解读用户周报并提炼下周行动项", "GENERAL",
                "饮食周报 一周趋势 平均评分 风险频率 下周建议",
                "优先解释周报中的平均分、结构变化和高频风险，再给出下周可执行建议。",
                List.of("周报", "一周", "这周", "本周", "weekly report"), 100);
    }

    @Bean
    AssistantSkill dietTrendSkill() {
        return skill("diet-trend", "分析多餐饮食趋势和重复风险", "GENERAL",
                "饮食趋势 最近记录 评分变化 高频风险 多样性",
                "比较最近记录而不是只评价单餐，说明趋势、证据和最优先改进项。",
                List.of("趋势", "最近吃", "最近饮食", "变化", "规律", "trend"), 95);
    }

    @Bean
    AssistantSkill friedFoodSkill() {
        return skill("fried-food-control", "控制油炸食品频率并优化同餐搭配", "FRIED",
                "油炸 高油 烹饪风险 炸鸡 脂肪控制",
                "围绕份量、频率、同餐蔬菜和下一餐平衡提出建议。",
                List.of("炸鸡", "油炸", "炸串", "薯条", "鸡排", "fried"), 90);
    }

    @Bean
    AssistantSkill barbecueSkill() {
        return skill("barbecue-balance", "优化烧烤和烤肉的肉类、蔬菜与蘸料组合", "BARBECUE",
                "烤肉 烧烤 红肉 高油 钠摄入 蔬菜搭配",
                "区分瘦肉和高脂肉，关注蘸料、盐分、主食和蔬菜搭配。",
                List.of("烤肉", "烧烤", "烤串", "烤鱼", "五花肉", "barbecue", "bbq"), 85);
    }

    @Bean
    AssistantSkill vegetableSkill() {
        return skill("vegetable-balance", "补足蔬菜同时避免蛋白质和主食不足", "VEGETABLE",
                "蔬菜 膳食纤维 维生素 蛋白质搭配",
                "判断蔬菜是否充足，并检查蛋白质和主食是否因只吃蔬菜而不足。",
                List.of("蔬菜", "青菜", "沙拉", "西兰花", "绿叶菜", "vegetable"), 80);
    }

    @Bean
    AssistantSkill sweetDrinkSkill() {
        return skill("sweet-drink-control", "控制甜饮甜品和高油主餐叠加", "SWEET_DRINK",
                "含糖饮料 甜品 糖摄入 控糖",
                "重点处理杯型、糖度、饮用频率以及与高油主餐的叠加风险。",
                List.of("奶茶", "饮料", "可乐", "甜品", "蛋糕", "糖", "dessert"), 75);
    }

    @Bean
    AssistantSkill fatLossSkill() {
        return skill("fat-loss", "根据用户记录生成可持续减脂饮食建议", "FAT_LOSS",
                "减脂 热量控制 蛋白质 蔬菜 主食",
                "避免极端节食，优先保证蛋白质和蔬菜，并结合用户常吃食物给出替换方案。",
                List.of("减脂", "减肥", "瘦", "控卡", "fat loss"), 70);
    }

    @Bean
    AssistantSkill muscleGainSkill() {
        return skill("muscle-gain", "根据训练恢复需求优化蛋白质和主食", "MUSCLE_GAIN",
                "增肌 蛋白质 碳水 训练 恢复 餐次",
                "围绕每餐蛋白质、训练前后主食和蔬菜摄入提出具体搭配。",
                List.of("增肌", "蛋白粉", "练肌肉", "训练后", "muscle"), 65);
    }

    @Bean
    AssistantSkill generalDietSkill() {
        return new KeywordAssistantSkill(new AssistantSkillPlan(
                "balanced-diet", "提供通用均衡饮食与食堂选餐建议", "GENERAL",
                "均衡饮食 主食 蛋白质 蔬菜 高油高糖风险",
                "使用主食、优质蛋白和蔬菜的餐盘框架，结合历史记录给出问题相关建议。"),
                List.of(), 0, true);
    }

    private AssistantSkill skill(String name,
                                 String description,
                                 String intent,
                                 String ragKeywords,
                                 String promptInstruction,
                                 List<String> keywords,
                                 int priority) {
        return new KeywordAssistantSkill(
                new AssistantSkillPlan(name, description, intent, ragKeywords, promptInstruction),
                keywords, priority, false);
    }
}
