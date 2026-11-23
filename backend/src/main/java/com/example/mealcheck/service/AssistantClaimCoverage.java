package com.example.mealcheck.service;

import java.util.*;
import java.util.regex.Pattern;

/** Exact, gap-free text spans prevent a reviewer from approving only the first half of a claim. */
final class AssistantClaimCoverage {
    private static final Pattern BOUNDARY=Pattern.compile("[，,。！？；;]|(?=因为|所以|因此|导致|从而|但是|然而|意味着|并且|而且|同时|使得|因而)");
    private static final Pattern CONDITION=Pattern.compile("如果|除非|只要|只有|仅在|只在|一旦|虽然|即使|尽管|无论|(?:^|[，,])若|(?:^|[，,])当|在.{0,20}(?:时|情况下|条件下)");
    private static final Pattern DEPENDENT=Pattern.compile("^(因为|所以|因此|从而|导致|否则|但是|然而|并且|而且|同时|使得|因而|意味着|就是|即为|等于|用.*(?:减去|扣除|相减)|它|其|这|则|也|并|且).*");
    private static final Pattern LARGE=Pattern.compile("(?:差异|差别|差距|相差)很大|很大的?(?:差异|差别)|明显|显著|大幅|大大|远高|远低|显然|significant|substantial",Pattern.CASE_INSENSITIVE);
    private static final Pattern CERTAIN=Pattern.compile("一定|必然|绝对|肯定|保证|总是|always|guarantee",Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALTHIER=Pattern.compile("更健康|更安全|更有营养|healthier|safer",Pattern.CASE_INSENSITIVE);
    private static final Pattern CAUSAL=Pattern.compile("导致|造成|引起|使得|从而|因此|因而|因为|因果|\\bcauses?\\b|\\bcausal(?:ity)?\\b|\\bbecause\\b",Pattern.CASE_INSENSITIVE);
    record Assertion(String id,String text,int start,int end,boolean standalone) { }
    private AssistantClaimCoverage() { }
    static List<Assertion> of(String id,String text) {
        var boundaries=new TreeSet<Integer>();boundaries.add(0);boundaries.add(text.length());
        var matcher=BOUNDARY.matcher(text);while(matcher.find()) {
            // Keep negation with its predicate: “不代表一定导致” must not become an affirmative “导致”.
            if(matcher.end()==matcher.start() && CAUSAL.matcher(text.substring(matcher.start())).lookingAt()
                    && negatedAt(text,matcher.start()))continue;
            boundaries.add(matcher.end()==matcher.start()?matcher.start():matcher.end());
        }
        var points=new ArrayList<>(boundaries);var spans=new ArrayList<int[]>();
        for(int i=0;i+1<points.size();i++) {
            int start=points.get(i),end=points.get(i+1);
            if(text.substring(start,end).matches("[\\s，,。！？；;]*") && !spans.isEmpty())spans.get(spans.size()-1)[1]=end;
            else if(end>start)spans.add(new int[]{start,end});
        }
        boolean temporalPreface=Pattern.compile("^[^，,。！？；;]{1,60}(?:之前|之后|以前|以后|前|后|时|情况下)[，,]").matcher(text.strip()).find();
        boolean conditional=CONDITION.matcher(text).find() || temporalPreface;var result=new ArrayList<Assertion>();
        for(int i=0;i<spans.size();i++) {
            int[] span=spans.get(i);String value=text.substring(span[0],span[1]);
            boolean attachedToNext=i+1<spans.size() && !value.matches("(?s).*[，,。！？；;]\\s*$")
                    && DEPENDENT.matcher(text.substring(spans.get(i+1)[0],spans.get(i+1)[1]).strip()).matches();
            boolean standalone=!DEPENDENT.matcher(value.strip()).matches() && (spans.size()==1 || !conditional && !attachedToNext);
            result.add(new Assertion(id+".a"+i,value,span[0],span[1],standalone));
        }
        return List.copyOf(result);
    }
    static String strengthGap(String text,String quotes) {
        // A hedge in another clause cannot qualify this clause's assertion.
        for(String clause:text.split("[，,。！？；;]|因为|但是|然而|并且|而且|同时")) {
            if(clause.isBlank() || quotes.contains(clause.strip())
                    || Pattern.compile("可能|未必|不一定|不能确定|无法确定|是否|假设|如果|不代表|不能断言").matcher(clause).find())continue;
            if(uncertainDifference(clause,quotes)
                    && Pattern.compile("(?:不同|不一样|并不相同|并非相同|有差别|存在差异)[。！？；;，,]*$").matcher(clause.strip()).find())
                return "来源只说明可能存在差异，不能改成无条件确定结论";
            if(Pattern.compile("(?:可能|未必|不一定)[^。！？；;，,]{0,8}(?:含有|包含|包括|计入)").matcher(quotes).find()
                    && Pattern.compile("(?:含有|包含|包括|计入)").matcher(clause).find()
                    && !Pattern.compile("不含|不包含|不包括|不计入|不能|无法").matcher(clause).find())
                return "来源只说明可能包含，不能断言本次必定包含";
        }
        if (Pattern.compile("(?:不一定|未必|可能)[^。！？；;]{0,12}(?:平均|相同|一样|一致)").matcher(quotes).find()
                && !quotes.contains(text.strip())
                && Pattern.compile("各人|每人|每个人|个人").matcher(text).find()
                && Pattern.compile("(?:并不|并非|都不|不)(?:平均|相同|一样|一致)").matcher(text).find()
                && !Pattern.compile("不一定|未必|可能|假设|如果|例如|比如").matcher(text).find())
            return "来源只说明可能不同，不能断言本次各人必定不同";
        if(positive(LARGE,text) && !positive(LARGE,quotes))return "程度描述缺少对应强度的原文";
        if(positive(CERTAIN,text) && !positive(CERTAIN,quotes))return "确定性或保证表述缺少原文依据";
        if(positive(HEALTHIER,text) && !positive(HEALTHIER,quotes))return "健康/安全比较缺少原文依据";
        if(positive(CAUSAL,text) && !informationLimit(text,quotes) && !weightComposition(text,quotes) && !positive(CAUSAL,quotes))return "因果表述缺少直接因果依据，相关或同时出现不能推出因果";
        return null;
    }
    private static boolean uncertainDifference(String text,String quotes) {
        Set<Integer> target=differenceTopics(text);
        for(String source:quotes.split("[，,。！？；;\\n]")) {
            if(!Pattern.compile("(?:可能|未必|不一定)[^。！？；;，,]{0,16}(?:不同|一样|相同|一致|差别|差异)").matcher(source).find())continue;
            Set<Integer> topics=differenceTopics(source);
            if(target.isEmpty() || !Collections.disjoint(target,topics))return true;
        }
        return false;
    }
    private static Set<Integer> differenceTopics(String text) {
        String[] topics={"营养|蛋白质|钙|成分|组成","原料|配方","重量|体积|称量|称重","各人|每人|个人|分配"};
        var found=new HashSet<Integer>();
        for(int i=0;i<topics.length;i++)if(Pattern.compile(topics[i]).matcher(text).find())found.add(i);
        return found;
    }
    static String contextGap(String text,String sourceContext) {
        if(text.contains("原味燕麦") && Pattern.compile("名称.{0,12}不能替代配料表|先确认是单一燕麦产品还是|原味.{0,20}不能替代").matcher(sourceContext).find())
            for(String clause:text.split("[，,。！？；;]")) {
                if(Pattern.compile("(?:原味燕麦|前者)(?:片|产品)?(?:就是|是|为|只含|仅含).{0,8}(?:单一|纯燕麦|燕麦)").matcher(clause).find()
                        && !Pattern.compile("是否|确认|核对|查看|若|如果|可能|不一定|未必|不代表|不能|不是").matcher(clause).find())
                    return "原味名称不能证明单一配料，应先核对实际产品配料";
            }
        if(Pattern.compile("用了酱.{0,3}或.{0,3}未用全部").matcher(sourceContext).find()
                && Pattern.compile("未用全部|未用完|没有用完").matcher(text).find()
                && !Pattern.compile("若|如果|假如|是否|例如在.{0,8}(?:确认|确实)|仅在|确认.{0,8}(?:未用|没有用)").matcher(text).find())
            return "记录示例中的未用全部须明确以用户确认该事实为条件，不能把来源中的或改为本次已发生";
        boolean difference=Pattern.compile("作差|差值|前后差|(?:减去|扣除).{0,16}(?:剩余|果皮|果核|重量)").matcher(text).find()
                && Pattern.compile("食用|吃下|摄入|移走").matcher(text).find();
        if(difference && Pattern.compile("损失|添加|洒出|分享|丢弃").matcher(sourceContext).find()
                && !Pattern.compile("(?:没有|无|不存在|排除)其他(?:损失|添加|变化)|(?:无损失|无添加)").matcher(text).find()
                && !(Pattern.compile("洒出|损失|丢弃").matcher(text).find() && Pattern.compile("分享|分食|添加").matcher(text).find()))
            return "作差归为本人食用量须保留其他损失、添加、分享及完整回称等适用条件；估算或有条件时不能代替这些前提";
        if(Pattern.compile("平均切|等分|均分|等大").matcher(sourceContext).find()
                && Pattern.compile("切成|切.*片|吃.*片").matcher(text).find()
                && Pattern.compile("[一二三四五六七八九十]+分之[一二三四五六七八九十]+|\\d+\\s*/\\s*\\d+|一半").matcher(text).find()
                && !Pattern.compile("平均|等分|均分|等大|均匀").matcher(text).find())
            return "按片数计算整份比例须说明等分条件，举例不能代替该条件";
        if(Pattern.compile("不(?:必|应)[^。！？]{0,20}一律否定").matcher(sourceContext).find())
            for(String clause:text.split("[，,。！？；;]|因为|但是|然而|并且|而且|同时")) {
                if(Pattern.compile("不能(?:直接)?(?:替代|取代)").matcher(clause).find()
                        && !Pattern.compile("仅凭|等量|所有|每种|按.{0,12}(?:重量|体积|数量)|无法直接换算").matcher(clause).find())
                    return "来源要求按具体产品区分，不能扩大成该类别一概不能替代";
            }
        return null;
    }
    static String coreAnswerGap(String question,String answer) {
        String dimensions=AssistantQuestionRequirements.coreGap(question,answer);
        if(dimensions!=null)return dimensions;
        var target=Pattern.compile("(?:是不是|是否)(?:就)?(?:完全)?(?:没有|不含)([\\p{IsHan}A-Za-z0-9]{1,15})(?=[，,。？！?\\s]|$)").matcher(question);
        if(target.find() && !answer.contains(target.group(1))
                && !Pattern.compile("完全没有|完全不含|不等于零|不是零|不能当作零").matcher(answer).find())
            return "漏答原问题关于“"+target.group(1)+"”的核心判断，不能只保留个人适用性或购买建议";
        return null;
    }
    private static boolean informationLimit(String text,String quotes) {
        // Only exempt an epistemic limitation, never an additional causal/health assertion.
        return text.strip().matches("(?:因此|因而|从而|所以)(?:仍然|仍|也|就)?(?:无法|不能|难以)(?:根据[^，,。！？；;]{1,12})?(?:可靠|准确|直接)?(?:确定|判断|推算|估算|知道|得知|确认)[^。！？；;]*[。！？；;]?")
                && !Pattern.compile("导致|造成|引起|使得|因为|因果|降低|增加|改善|治疗|预防").matcher(text).find()
                && Pattern.compile("无法|不能|难以|不足|缺少|缺乏|不代表|不等于|不能仅凭").matcher(quotes).find();
    }
    private static boolean weightComposition(String text,String quotes) {
        // An explanation of what was weighed, never an exemption for health/causal claims.
        if(!text.strip().matches("因为(?:秤上(?:的)?读数|称量(?:的)?(?:重量|读数)|整份重量|毛重)(?:还|也)?(?:包含|包括|含有)(?:容器|包装|果皮|果核|等|和|与|及|、|不可食部分|的|部分)+[。！？；;，,]?"))return false;
        if(!Pattern.compile("称量|重量|称重").matcher(quotes).find() || !Pattern.compile("区分|包含|包括|扣除|减去|去皮重").matcher(quotes).find())return false;
        boolean component=false;
        for(String item:List.of("容器","包装","果皮","果核"))if(text.contains(item)) {
            component=true;if(!quotes.contains(item))return false;
        }
        return component;
    }
    private static boolean positive(Pattern pattern,String text) {
        var matcher=pattern.matcher(text);
        while(matcher.find()) {
            if(!negatedAt(text,matcher.start()))return true;
        }
        return false;
    }
    private static boolean negatedAt(String text,int offset) {
        String clause=text.substring(0,offset).replaceFirst("(?s)^.*[，,。！？；;\\n]","")
                .replaceFirst("(?s)^.*(?:但是|然而|可是|却|反而|但)","");
        // Double negation must never grant an exemption to an unsupported positive claim.
        if(clause.matches(".*(?:(?:并非|并不是|不是|不能说|不能认为|不代表|不意味着|未必)(?:完全|一定|绝对|就)?(?:不能|不会|没有|无法|不可能|不)|(?:不能|不可|不得)不).{0,48}$"))return false;
        // These constructions scope over an object, rather than a fixed distance from a keyword.
        if(clause.matches(".*(?:不能|不可|不应|无法|不宜)(?:把|将).{1,48}(?:当作|当成|视作|视为|看作|看成|认作|认定为|作为)(?:(?!并|且|也|而|却|同时|此外|另外).){0,32}$"))return true;
        String prefix=clause.substring(Math.max(0,clause.length()-10));
        return prefix.matches(".*(?:不|无|未|没|无法|不能|并非|未必|不一定|不代表|不意味).{0,4}$");
    }
    static String standaloneText(String text) {
        String value=text.strip().replaceFirst("[，,；;：:]+$","");
        return value.matches("(?s).*[。！？]$")?value:value+"。";
    }
}
