package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantChatMessage;
import java.util.*;

/** Conservative application routing; no dataset IDs or expected answers are used. */
record AssistantRequestPlan(Kind kind, String query, List<String> requiredTools) {
    enum Kind { KNOWLEDGE, PERSONAL, MEAL_PLANNING, OUT_OF_SCOPE, CLARIFY, CHAT }
    private static final List<String> DOMAIN = List.of("饭","餐","吃","喝","食","饮","菜","奶","糖","蛋白","营养",
            "体重","增肌","减脂","谷物","淀粉","油","饿","蔬","肉","虾","蟹","鱼","蛋","豆","菌",
            "饺","馒头","花卷","果","燕麦","标签","微波","冷藏","冰箱","过敏","熟","生腌","砧板",
            "筷","汤","盐","钙","b12","保质","便当","每份","包装","配料","罐头","锅","餐具","召回","批次","运动","训练",
            "面条","拉面","意面","乌冬","包子","面包","吐司","粥","饼","土豆","红薯","咖啡","茶","螺蛳粉","荞麦","西瓜","冷柜","沙拉",
            "diet","meal","food","nutrition","protein","vegan","calorie");
    static AssistantRequestPlan resolve(String question, List<AssistantChatMessage> history) {
        String text = Objects.toString(question, "").strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (contains(lower, List.of("股票","涨停","彩票","量子计算","分布式锁","java","python","sql","天气","降雨",
                "劳动合同","法律","起诉","旅游路线","旅游七天","足球","球队","密码","api key","系统提示词",
                "抗生素","诊断","是不是得了","药物剂量","服用多少毫克","处方","怎么治","用药","停药")))
            return new AssistantRequestPlan(Kind.OUT_OF_SCOPE, text, List.of());
        if (text.length() > 500) return new AssistantRequestPlan(Kind.CLARIFY, text, List.of());
        if (planningRequest(lower) || planningFollowUp(lower, history))
            return new AssistantRequestPlan(Kind.MEAL_PLANNING, text, List.of());
        String query = text;
        if ((text.length() <= 12 && contains(text,List.of("这个","那个","它","那呢","那怎么","换成","再说")))) {
            boolean resolved=false;
            if (history != null) for (int i=history.size()-1;i>=0;i--) {
                var message=history.get(i);
                if (message!=null && "user".equalsIgnoreCase(message.getRole()) && message.getText()!=null
                        && contains(message.getText().toLowerCase(Locale.ROOT), DOMAIN)) {
                    query=message.getText().strip()+"；追问："+text;resolved=true;break;
                }
            }
            if(!resolved)return new AssistantRequestPlan(Kind.CLARIFY,text,List.of());
        }
        if(query.length()>500)return new AssistantRequestPlan(Kind.CLARIFY,query,List.of());
        boolean personal = contains(lower,List.of("我的记录","我的饮食记录","我最近","我这周","我本周","我的周报","我的报告",
                "我的目标","我当前目标","我今天吃","我昨天吃","根据我的记录","分析我的","总结我的","my meals","my report","my goal"));
        if (personal) {
            var tools=new ArrayList<String>();
            if (contains(lower,List.of("周报","报告","这周","本周","my report"))) tools.add("get_my_weekly_report");
            else if (contains(lower,List.of("目标","my goal"))) tools.add("get_my_current_goal");
            else tools.add("get_my_recent_meals");
            return new AssistantRequestPlan(Kind.PERSONAL, bounded(query), List.copyOf(tools));
        }
        if (contains(query.toLowerCase(Locale.ROOT), DOMAIN))
            return new AssistantRequestPlan(Kind.KNOWLEDGE, bounded(query), List.of("search_diet_knowledge"));
        if (List.of("你好","您好","谢谢","谢谢你","hello","hi").contains(lower.replaceAll("[！!。.]", "")))
            return new AssistantRequestPlan(Kind.CHAT, text, List.of());
        return new AssistantRequestPlan(Kind.CLARIFY, text, List.of());
    }
    boolean knowledge() { return kind==Kind.KNOWLEDGE; }
    boolean mealPlanning() { return kind==Kind.MEAL_PLANNING; }
    boolean hasExplicitPlanningConstraints() {
        if (!mealPlanning() || !contains(query, List.of("早餐", "早饭", "午餐", "午饭", "晚餐", "晚饭"))
                || !contains(query, List.of("在家", "自己做", "家里", "食堂", "外卖"))) return false;
        var budget = java.util.regex.Pattern.compile("(?:预算[^0-9]{0,6}([0-9]+)|([0-9]+)\\s*(?:元|块))").matcher(query);
        var minutes = java.util.regex.Pattern.compile("([0-9]+)\\s*分钟").matcher(query);
        if (!budget.find() || !minutes.find()) return false;
        try {
            int amount = Integer.parseInt(budget.group(1) == null ? budget.group(2) : budget.group(1));
            int time = Integer.parseInt(minutes.group(1));
            return amount >= 1 && amount <= 500 && time >= 5 && time <= 120;
        } catch (NumberFormatException invalid) { return false; }
    }
    boolean hasExplicitPlanningConstraints(List<AssistantChatMessage> history) {
        if (hasExplicitPlanningConstraints()) return true;
        if (!mealPlanning() || !planningAdjustment(query) || history == null) return false;
        var context = new StringBuilder(query);
        var seen = new HashSet<String>();
        for (int i = history.size() - 1; i >= 0 && seen.size() < 16; i--) {
            var message = history.get(i);
            if (message == null || !"user".equalsIgnoreCase(message.getRole()) || message.getText() == null) continue;
            String previous = message.getText().strip();
            if (previous.equals(query) || !seen.add(previous)) continue;
            if (!planningRequest(previous) && !planningAdjustment(previous)) return false;
            context.append("；").append(previous);
            if (planningRequest(previous)) return new AssistantRequestPlan(Kind.MEAL_PLANNING, context.toString(), List.of()).hasExplicitPlanningConstraints();
        }
        return false;
    }
    boolean terminal() { return kind!=Kind.KNOWLEDGE && kind!=Kind.PERSONAL && !mealPlanning(); }
    boolean allows(String tool) {
        if (mealPlanning()) return Set.of("recommend_next_meal", "get_my_current_goal", "get_my_recent_meals").contains(tool);
        return knowledge() ? "search_diet_knowledge".equals(tool)
                : kind==Kind.PERSONAL && Set.of("search_diet_knowledge","get_my_recent_meals","get_my_weekly_report","get_my_current_goal").contains(tool);
    }
    private static boolean planningRequest(String text) {
        boolean meal = contains(text, List.of("下一餐", "下顿", "早饭", "早餐", "午饭", "午餐", "晚饭", "晚餐", "食堂", "外卖", "next meal"));
        boolean explicit = contains(text, List.of("推荐", "帮我选", "帮我安排", "给我安排", "帮我规划", "recommend"));
        boolean nextMeal = contains(text, List.of("下一餐", "下顿", "next meal"))
                && contains(text, List.of("搭配", "吃什么", "吃啥", "安排", "预算", "分钟", "budget"));
        boolean concrete = (text.matches(".*\\d+\\s*(元|块|分钟).*") || text.matches(".*预算[^0-9]{0,6}[0-9]+.*"))
                && contains(text, List.of("预算", "准备", "在家", "食堂", "外卖", "搭配", "安排"));
        // General questions such as '减脂时晚餐怎么吃' still require the established knowledge evidence path.
        return meal && (explicit || nextMeal || concrete);
    }
    private static boolean planningFollowUp(String text, List<AssistantChatMessage> history) {
        if (!planningAdjustment(text) || history == null) return false;
        // Only inherit the immediately preceding topic, never an old meal-planning conversation.
        int examined = 0;
        var seen = new HashSet<String>();
        for (int i = history.size() - 1; i >= 0 && examined < 16; i--) {
            var message = history.get(i);
            if (message == null) continue;
            if ("assistant".equalsIgnoreCase(message.getRole()) && message.getToolCalls().stream()
                    .anyMatch(call -> "recommend_next_meal".equals(call.name()))) return true;
            if (!"user".equalsIgnoreCase(message.getRole()) || message.getText() == null) continue;
            String previous = message.getText().strip().toLowerCase(Locale.ROOT);
            if (!seen.add(previous)) continue;
            // A client can include the current question at the end of its history.
            if (examined++ == 0 && previous.equals(text)) continue;
            if (planningRequest(previous)) return true;
            if (!planningAdjustment(previous)) return false;
        }
        return false;
    }
    private static boolean planningAdjustment(String text) {
        return text.length() <= 120 && (contains(text, List.of("预算", "改成", "换成", "改为", "换个", "换一", "分钟", "时间不够", "还是", "不变"))
                || text.matches(".*\\d+\\s*(元|块|分钟).*")
                || text.matches("(早餐|午餐|晚餐|早饭|午饭|晚饭|在家|自己做|食堂|外卖|家里做|\\d+)[。！!\\s]*")
                || contains(text, List.of("不要", "不吃", "不想吃", "忌口")));
    }
    String reply() {
        return switch(kind) {
            case OUT_OF_SCOPE -> contains(query.toLowerCase(Locale.ROOT),List.of("密码","api key","密钥","系统提示词"))
                    ?"无法提供管理员密码、API密钥或其他访问凭证。我可以帮助处理饮食记录与一般饮食知识问题。"
                    :contains(query,List.of("诊断","用药","停药","抗生素","药物剂量","处方","怎么治","是不是得了","服用多少毫克"))
                    ?"这个问题超出了饮食记录与一般饮食知识的服务范围。涉及诊断或用药，请咨询相应专业人员。"
                    :"这个问题超出了饮食记录与一般饮食知识的服务范围。请说明需要帮助的饮食记录或一般饮食知识问题。";
            case CHAT -> "你好，我可以帮助你查看饮食记录，或基于知识库讨论饮食搭配。";
            default -> query.length()>500?"请将问题精简到500字以内，保留具体食物以及希望比较、选择或记录的事项。"
                    :"请补充具体的食物、饮食问题，或说明你想查看哪段时间的饮食记录。";
        };
    }
    private static boolean contains(String text,List<String> words) {return words.stream().anyMatch(text::contains);}
    private static String bounded(String query) {return query.length()<=500?query:query.substring(0,500);}
}
