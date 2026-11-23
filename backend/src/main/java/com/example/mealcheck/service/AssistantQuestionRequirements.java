package com.example.mealcheck.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;

/** Narrow input contracts from the user's current words, independent of knowledge sufficiency. */
final class AssistantQuestionRequirements {
    private AssistantQuestionRequirements() { }
    private static boolean has(String pattern,String text) {return Pattern.compile(pattern).matcher(text).find();}
    static boolean combinationAndStorage(String question) {
        return has("怎么|怎样|如何",question) && has("组合|搭配|简餐",question)
                && has("核对|确认|查看|检查",question) && has("保存|储存|冷藏|保质|开封",question);
    }
    static String coreGap(String question,String answer) {
        if(!combinationAndStorage(question))return null;
        if(!has("主食|蛋白|蛋类|鸡蛋|搭配|组合",answer))return "多问句漏答餐食组合；分别用简短ANSWER覆盖组合与保存核对";
        if(!has("保存|储存|冷藏|保质|开封",answer) || !has("核对|查看|确认|标签|包装|要求",answer))
            return "多问句漏答保存要求核对；先给每个核心问题1至2分句，不把预算全部用于组合细节";
        return null;
    }
    static JsonNode normalizeAssessment(String question,JsonNode verdict) {
        if(!(verdict instanceof ObjectNode) || !valid(verdict.path("inputMissing")) || !valid(verdict.path("knowledgeMissing"))
                || verdict.path("inputMissing").size()+verdict.path("knowledgeMissing").size()>5)return verdict;
        var result=((ObjectNode)verdict).deepCopy();var gaps=new LinkedHashSet<String>();
        verdict.path("inputMissing").forEach(v->gaps.add(v.asText()));
        // General inference questions do not request a personalized or measured result.
        boolean general=has("是不是|是否|就能保证",question)
                && has("(?:低因|脱咖啡因).*(?:没有|不含|咖啡因|随便)|(?:看着|看起来|外观).*(?:是不是|是否|保证)",question)
                && !has("我|本人|孕|用药|疾病|敏感|摄入了|喝了|算出|计算|多少|几克|几毫克",question);
        if(general && verdict.path("answersQuestion").isBoolean() && verdict.path("answersQuestion").asBoolean())
            gaps.removeIf(g->has("敏感|耐受|体质|产品|咖啡因含量|配方|标签|调味|汤底|喝下|实际.*量|饮用量",g));
        String required=null;
        // Only explicit absent facts are enforced. Do not infer an absence from a topic alone.
        if(has("照片",question) && has("油|酱",question)
                && currentlyMissing(question,"配方|用量记录","配方|实际用量|用油量"))
            required="酱料配方或实际用量记录";
        if(has("共享|整桌|火锅|分食",question)
                && currentlyMissing(question,"个人分食|剩余记录","个人分食|分食记录|剩余记录"))
            required="个人分食及剩余记录";
        if(required!=null) {
            String topic=required.startsWith("酱料")?"配方|用量|油量":"分食|剩余|个人实际";
            // Never silently erase a known gap to satisfy the size limit: the existing review
            // validator will reject an overflowing assessment and request a concise repair.
            if(gaps.stream().noneMatch(g->has(topic,g)))gaps.add(required);
        }
        var normalized=result.putArray("inputMissing");gaps.forEach(normalized::add);
        if(!normalized.equals(verdict.path("inputMissing")))result.set("modelInputMissing",verdict.path("inputMissing").deepCopy());
        return result;
    }
    private static boolean valid(JsonNode values) {
        if(!values.isArray() || values.size()>5)return false;
        for(var v:values)if(!v.isTextual() || v.asText().isBlank() || v.asText().length()>70)return false;
        return true;
    }
    private static boolean currentlyMissing(String question,String missingTopic,String providedTopic) {
        int missing=last("(?:没有|缺少|未提供)[^。！？]{0,20}(?:"+missingTopic+")",question);
        int provided=last("(?:已补齐|已提供|现在有|现已|已经记录|有了)[^。！？]{0,20}(?:"+providedTopic+")|(?:"+providedTopic+")[^。！？]{0,8}(?:已补齐|已提供|已有|已记录)",question);
        return missing>=0 && missing>provided;
    }
    private static int last(String pattern,String text) {
        var matcher=Pattern.compile(pattern).matcher(text);int found=-1;while(matcher.find())found=matcher.start();return found;
    }
}
