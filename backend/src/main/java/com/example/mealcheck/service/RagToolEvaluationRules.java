package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Deterministic tool/argument rules; topic matching is a lexical proxy, not semantic grading. */
final class RagToolEvaluationRules {
    static final String VERSION = "rag-tools-v2";
    static final String SEARCH = "search_diet_knowledge";
    static final Set<String> TOOLS = Set.of(SEARCH,"get_my_recent_meals","get_my_weekly_report","get_my_current_goal");
    private RagToolEvaluationRules() { }

    static void validate(JsonNode rules, boolean noAnswer) {
        if (!VERSION.equals(rules.path("ruleVersion").asText())) throw new IllegalArgumentException("RAG 工具规则版本无效");
        Set<String> expected = names(rules.path("expectedTools"));
        Set<String> forbidden = names(rules.path("forbiddenTools"));
        if (!expected.equals(noAnswer ? Set.of() : Set.of(SEARCH))
                || !forbidden.equals(noAnswer ? TOOLS : Set.of("get_my_recent_meals","get_my_weekly_report","get_my_current_goal")))
            throw new IllegalArgumentException("RAG 工具标注缺少预期或禁用工具");
        if (rules.path("maxToolCalls").asInt(-1)!=(noAnswer?0:2)) throw new IllegalArgumentException("RAG 工具次数标注无效");
        JsonNode groups=rules.path("queryTopicGroups");
        if (!groups.isArray() || (!noAnswer && groups.isEmpty())) throw new IllegalArgumentException("缺少查询主题标注");
        if (noAnswer && (!groups.isEmpty() || !names(rules.path("expectedToolSequence")).isEmpty()
                || !rules.path("expectedToolStatuses").isObject() || !rules.path("expectedToolStatuses").isEmpty()))
            throw new IllegalArgumentException("库外题不应配置检索主题、顺序或成功状态");
        for (var group:groups) if (!group.isArray() || group.isEmpty() || names(group).size()!=group.size())
            throw new IllegalArgumentException("查询主题组必须为非空、去重的词语数组");
        if (rules.path("limitMin").asInt()!=1 || rules.path("limitMax").asInt()!=5)
            throw new IllegalArgumentException("检索条数须与生产工具的 1～5 一致");
        if (!noAnswer && (!names(rules.path("expectedToolSequence")).equals(Set.of(SEARCH))
                || !names(rules.path("expectedToolStatuses").path(SEARCH)).equals(Set.of("SUCCESS"))))
            throw new IllegalArgumentException("缺少知识工具顺序或成功状态标注");
    }

    static List<String> assess(JsonNode rules, List<String> actual,
                               List<AssistantFunctionCallingService.ToolCallTrace> trace, ObjectMapper mapper) {
        var failures=new ArrayList<String>();
        var calls=trace==null?List.<AssistantFunctionCallingService.ToolCallTrace>of():trace;
        if (actual.size()>rules.path("maxToolCalls").asInt()) failures.add("超过标注的工具调用次数上限");
        Set<String> allowed = names(rules.path("expectedTools"));
        if (actual.stream().anyMatch(tool -> !allowed.contains(tool))) failures.add("调用了本题未允许的工具");
        if (!actual.equals(calls.stream().map(AssistantFunctionCallingService.ToolCallTrace::name).toList()))
            failures.add("实际工具名称与调用轨迹不一致，无法核验参数");
        var seenQueries=new HashSet<String>();
        for (var call:calls) {
            if (!"SUCCESS".equalsIgnoreCase(call.status())) failures.add(call.name()+" 调用未成功："+call.status());
            if (!SEARCH.equals(call.name())) continue; // Selection/forbidden checks are shared with the existing suite.
            try {
                JsonNode args=mapper.readTree(call.arguments());
                if (args==null || !args.isObject()) throw new IllegalArgumentException();
                var fields=args.fieldNames();
                while(fields.hasNext()) if (!Set.of("query","limit").contains(fields.next())) throw new IllegalArgumentException();
                JsonNode query=args.path("query");
                if (!query.isTextual() || query.asText().isBlank() || query.asText().length()>500) throw new IllegalArgumentException();
                JsonNode limit=args.get("limit");
                int count=3; // Same optional default as the production tool.
                if (limit!=null && !limit.isNull()) {
                    if (!limit.isIntegralNumber() || !limit.canConvertToInt()) throw new IllegalArgumentException();
                    count=limit.intValue();
                }
                if (count<rules.path("limitMin").asInt() || count>rules.path("limitMax").asInt()) throw new IllegalArgumentException();
                String text=query.asText().strip().toLowerCase(Locale.ROOT);
                for (var group:rules.path("queryTopicGroups")) {
                    boolean matched=false;
                    for(var term:group) if(text.contains(term.asText().toLowerCase(Locale.ROOT))) {matched=true;break;}
                    if(!matched) failures.add("检索 query 未覆盖标注主题组："+group);
                }
                if (!seenQueries.add(text.replaceAll("\\s+", "")+":"+count)) failures.add("重复执行等价知识查询");
            } catch(Exception invalid) { failures.add("知识工具参数格式或 limit 范围不合规"); }
        }
        return List.copyOf(new LinkedHashSet<>(failures));
    }

    private static Set<String> names(JsonNode array) {
        var values=new HashSet<String>();
        if (!array.isArray()) throw new IllegalArgumentException("工具规则必须使用数组");
        for(var item:array) {
            if(!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("规则词语不能为空");
            values.add(item.asText());
        }
        return values;
    }
}
