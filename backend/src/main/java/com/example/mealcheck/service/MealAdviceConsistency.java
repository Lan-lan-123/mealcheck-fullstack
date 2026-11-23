package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.RecognitionResult;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.mealcheck.service.MealAdviceRiskPolicy.Risk;

/** Targeted fact checks, not a general natural-language verifier. Never makes another provider call. */
final class MealAdviceConsistency {
    private MealAdviceConsistency() { }
    private enum Verdict { PASS, UNNEEDED_REVIEW, FACT_CONFLICT, UNKNOWN_ATTRIBUTE_ASSERTED }
    enum Reason { EMPTY_ADVICE, FACT_CONFLICT, UNKNOWN_ATTRIBUTE_ASSERTED, UNNEEDED_REVIEW }
    record Finding(Reason reason,String branch,String sentence,String food,String attribute,Boolean knownValue) {
        @Override public String toString() { return reason+":"+branch; }
    }
    record Assessment(Set<Reason> reasons,List<Finding> findings) {
        @Override public String toString() { return "Advice assessment [reasons="+reasons+", findings="+findings.size()+"]"; }
    }
    static Assessment assess(String advice,RecognitionResult recognition) {
        var reasons=EnumSet.noneOf(Reason.class);
        var findings=new ArrayList<Finding>();
        if(advice==null || advice.isBlank())reasons.add(Reason.EMPTY_ADVICE);
        else {
            var sentences=SENTENCE.matcher(advice);
            while(sentences.find()) {
                var verdict=inspectSentence(sentences.group(),foods(recognition),findings);
                if(verdict!=Verdict.PASS)reasons.add(Reason.valueOf(verdict.name()));
            }
        }
        return new Assessment(Set.copyOf(reasons),List.copyOf(findings));
    }
    private record Mention(FoodItem food, int start, int end) { }
    private record RiskMention(Risk risk, int start, int end) { }
    private static final Pattern NON_MEAT_GROUP = Pattern.compile("(?:鸡蛋|鸭蛋|蛋类|豆腐|豆浆|牛奶|乳制品)[^。！？；\n]{0,30}等(?:明确的?|各类)?肉类");
    private static final Pattern NON_MEAT_ASSERTION = Pattern.compile("(?:鸡蛋|鸭蛋|蛋类|豆腐|豆浆|牛奶|乳制品)(?:属于|是)(?:明确的?)?肉类");
    private static final Pattern NEGATED = Pattern.compile("不是|不属于|不能|无法|不代表|没有证据|未确认|尚未确认|不一定|并非|非油炸|未油炸|未经过油炸|待核对|需核对|是否");
    private static final Pattern FUTURE = Pattern.compile("下一餐|下餐|下次|今后|以后|未来|后续(?:用餐|就餐|餐次)?");
    private static final Pattern PROCESSED = Pattern.compile("加工肉|香肠|火腿|培根|午餐肉|熏肉");
    private static final Pattern INTAKE = Pattern.compile("(?:蔬菜|蛋白质|主食|营养)[^。！？；，,]{0,8}摄入(?:明显)?(?:不足|过少|充足|达标|过量)|蔬菜明显不足");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？；;\n]+[。！？；;\n]*");
    private static final Pattern RISKS = Pattern.compile("油炸或高油|油炸|炸制|高油(?:风险)?|油腻|油脂较多|用油过多");
    private static final Pattern REVIEW = Pattern.compile("核对|核实|确认|查明|检查");
    private static final Pattern RISK_UNITS = Pattern.compile("[，,]|但是|然而|不过(?!于|度|分)|而且|并且|同时|而是|但|且|却");
    private static final Pattern MEAL_ASSERTION = Pattern.compile("(?:本餐|这餐|当前餐食).*(?:含有|包含|存在|属于|采用|有)");
    private static final Pattern MEAL_SCOPE = Pattern.compile("本餐|这餐|这顿饭|当前餐食|系统提示|风险标签|识别结果显示");
    private enum Stance { UNKNOWN, REVIEW, PREVENTION, CONDITIONAL, NEGATIVE, POSITIVE }
    private static final String STATE_PREFIX="(?:属性|风险|状态|情况|程度|目前|当前|暂时|暂|仍然|仍|尚|还|为|是|的){0,8}";
    private static final Pattern AFFIRMED_STATE=Pattern.compile("^"+STATE_PREFIX+"(?:不是未知|并非未知|不再未知|已确认|明确存在|确实存在).*" );
    private static final Pattern UNKNOWN_STATE=Pattern.compile("^"+STATE_PREFIX+"(?:未知|不确定|不明确|不清楚|尚不清楚|无法确定|无法判断|尚未确认|无法确认|待核对|需核对|不明|未发现|未识别).*" );
    private static final Pattern ABSENT_STATE=Pattern.compile("^"+STATE_PREFIX+"(?:否|false|不存在|并不存在|没有|不成立).*" );

    static boolean consistent(String advice, RecognitionResult recognition) {
        if (advice == null || advice.isBlank()) return false;
        var sentences = SENTENCE.matcher(advice);
        while (sentences.find()) if (inspectSentence(sentences.group(), foods(recognition)) != Verdict.PASS) return false;
        return true;
    }

    /** Remove only a sentence whose sole issue is an unsupported review request. Keep factual failures for fallback. */
    static String removeUnnecessaryReviews(String advice, RecognitionResult recognition) {
        if (advice == null) return "";
        var result = new StringBuilder();
        var sentences = SENTENCE.matcher(advice);
        while (sentences.find()) {
            String sentence = sentences.group();
            if (inspectSentence(sentence, foods(recognition)) != Verdict.UNNEEDED_REVIEW) result.append(sentence);
        }
        return result.toString().strip();
    }

    private static List<FoodItem> foods(RecognitionResult recognition) {
        return recognition == null || recognition.getFoods() == null ? List.of() : recognition.getFoods();
    }

    private static Verdict inspectSentence(String sentence, List<FoodItem> foods) {
        return inspectSentence(sentence,foods,null);
    }
    private static void finding(List<Finding> findings,Reason reason,String branch,String sentence,String food,Risk risk,Boolean value) {
        if(findings!=null && findings.size()<32) findings.add(new Finding(reason,branch,sentence.substring(0,Math.min(512,sentence.length())),
                food==null?"":food.substring(0,Math.min(80,food.length())),risk==null?"":risk.name(),value));
    }
    private static Verdict inspectSentence(String sentence,List<FoodItem> foods,List<Finding> findings) {
        boolean processed = foods.stream().anyMatch(food -> PROCESSED.matcher(food.getName()).find());
        boolean unnecessary = false;
        boolean unknownAssertion = false;
        List<FoodItem> inherited = List.of();
        Risk inheritedRisk=null;
        boolean inheritedMealScope=false;
        for (String unit : RISK_UNITS.split(sentence)) {
            if (!NEGATED.matcher(unit).find()) {
                if (NON_MEAT_GROUP.matcher(unit).find() || NON_MEAT_ASSERTION.matcher(unit).find() || INTAKE.matcher(unit).find()) {
                    finding(findings,Reason.FACT_CONFLICT,"FOOD_GROUP_OR_INTAKE",sentence,"",null,null);return Verdict.FACT_CONFLICT;
                }
                if (!processed && PROCESSED.matcher(unit).find() && unit.matches(".*(?:长期|依赖|经常|本餐|这餐|已吃|吃了|含有|包含).*")) {
                    finding(findings,Reason.FACT_CONFLICT,"UNSUPPORTED_PROCESSED_MEAT",sentence,"",null,null);return Verdict.FACT_CONFLICT;
                }
            }
            var mentions = foodMentions(unit, foods);
            var risks = new ArrayList<RiskMention>();
            var matcher = RISKS.matcher(unit);
            while (matcher.find()) risks.add(new RiskMention(matcher.group().equals("油炸或高油") ? Risk.EITHER
                    : matcher.group().matches("油炸|炸制") ? Risk.FRIED : Risk.HIGH_OIL, matcher.start(), matcher.end()));
            // A following predicate such as “但确实存在” still asserts the preceding attribute.
            boolean inheritedPredicate=risks.isEmpty() && mentions.isEmpty() && inheritedRisk!=null && (!inherited.isEmpty() || inheritedMealScope)
                    && compact(unit).matches("^(?:该|此|这个|这一)?(?:风险|属性)?(?:已确认|明确存在|确实存在|确定存在|确实不存在|已确认没有|已确认为否)[。！？；;]*$");
            if(inheritedPredicate)
                risks.add(new RiskMention(inheritedRisk,0,0));
            boolean riskMealScope=false;
            for (int i = 0; i < risks.size(); i++) {
                var risk = risks.get(i);
                var subjects = subjects(unit, risk, mentions);
                boolean mealScope = subjects.isEmpty() && (MEAL_SCOPE.matcher(unit).find() || inheritedPredicate && inheritedMealScope);
                riskMealScope=mealScope;
                if (subjects.isEmpty() && !mealScope) subjects = inherited;
                int left = i == 0 ? 0 : risks.get(i - 1).end();
                int right = i + 1 == risks.size() ? unit.length() : risks.get(i + 1).start();
                // A later named food cannot qualify an earlier food's risk assertion as unknown.
                for (var mention : mentions) if (mention.start() >= risk.end()) right = Math.min(right, mention.start());
                boolean review = reviewRequest(unit) || (unit.contains("是否") && reviewRequest(sentence));
                Stance stance=stance(unit.substring(left,risk.start()),unit.substring(risk.end(),right),review);
                if (review && (stance==Stance.REVIEW || stance==Stance.UNKNOWN)) {
                    for (var subject : subjects) if (!MealAdviceRiskPolicy.shouldReview(subject, risk.risk())) {
                        unnecessary=true;finding(findings,Reason.UNNEEDED_REVIEW,"REVIEW_WITHOUT_CUE",sentence,subject.getName(),risk.risk(),MealAdviceRiskPolicy.value(subject,risk.risk()));
                    }
                    if(mealScope && subjects.isEmpty() && foods.stream().noneMatch(food->MealAdviceRiskPolicy.shouldReview(food,risk.risk()))) {
                        unnecessary=true;finding(findings,Reason.UNNEEDED_REVIEW,"MEAL_REVIEW_WITHOUT_CUE",sentence,"本餐",risk.risk(),null);
                    }
                }
                boolean future = !MEAL_SCOPE.matcher(unit).find() && FUTURE.matcher(unit.substring(0,risk.start())).find()
                        || !MEAL_SCOPE.matcher(unit).find() && sentence.stripLeading().matches("^(?:建议|可考虑|可以)?(?:在)?(?:下一餐|下餐|下次|今后|以后|未来|后续(?:用餐|就餐|餐次)?).*");
                if ((stance==Stance.POSITIVE || stance==Stance.NEGATIVE) && !future) {
                    boolean asserted=stance==Stance.POSITIVE;
                    for (var subject : subjects) {
                        Boolean value=MealAdviceRiskPolicy.value(subject,risk.risk());
                        if(value!=null && value!=asserted) {
                            finding(findings,Reason.FACT_CONFLICT,asserted?"POSITIVE_CONTRADICTS_FALSE":"NEGATIVE_CONTRADICTS_TRUE",sentence,subject.getName(),risk.risk(),value);return Verdict.FACT_CONFLICT;
                        }
                        if(value==null) {
                            unknownAssertion=true;finding(findings,Reason.UNKNOWN_ATTRIBUTE_ASSERTED,asserted?"UNKNOWN_AS_PRESENT":"UNKNOWN_AS_ABSENT",sentence,subject.getName(),risk.risk(),null);
                        }
                    }
                    if (subjects.isEmpty() && (mealScope || MEAL_ASSERTION.matcher(unit.substring(0, risk.start())).find())) {
                        boolean anyTrue=foods.stream().anyMatch(food -> Boolean.TRUE.equals(MealAdviceRiskPolicy.value(food,risk.risk())));
                        boolean anyUnknown=foods.stream().anyMatch(food -> MealAdviceRiskPolicy.value(food,risk.risk())==null);
                        boolean contradicted=asserted?!anyTrue&&!anyUnknown:anyTrue;
                        if(contradicted) {
                            finding(findings,Reason.FACT_CONFLICT,"MEAL_RISK_CONFLICT",sentence,"本餐",risk.risk(),!asserted);return Verdict.FACT_CONFLICT;
                        }
                        if(anyUnknown && (!asserted || !anyTrue)) {
                            unknownAssertion=true;finding(findings,Reason.UNKNOWN_ATTRIBUTE_ASSERTED,asserted?"MEAL_UNKNOWN_AS_PRESENT":"MEAL_UNKNOWN_AS_ABSENT",sentence,"本餐",risk.risk(),null);
                        }
                    }
                }
            }
            if (!mentions.isEmpty()) inherited = precedingGroup(unit, mentions, unit.length());
            if(!risks.isEmpty()){inheritedRisk=risks.get(risks.size()-1).risk();inheritedMealScope=riskMealScope;}
            else if(!mentions.isEmpty()){inheritedRisk=null;inheritedMealScope=false;}
        }
        return unknownAssertion ? Verdict.UNKNOWN_ATTRIBUTE_ASSERTED : unnecessary ? Verdict.UNNEEDED_REVIEW : Verdict.PASS;
    }

    private static Stance stance(String before,String after,boolean review) {
        String prefix = compact(before), suffix = compact(after).replaceFirst("^(?:该|此|这个|这一)(?:风险|属性)","");
        if(prefix.matches("^(?:若|如果|假如|倘若)[^。！？；，,]*$"))return Stance.CONDITIONAL;
        if(suffix.matches("^"+STATE_PREFIX+"(?:已确认|明确|确实)(?:没有|不存在|为否|为false).*"))return Stance.NEGATIVE;
        if(AFFIRMED_STATE.matcher(suffix).matches())return Stance.POSITIVE;
        if(UNKNOWN_STATE.matcher(suffix).matches())return Stance.UNKNOWN;
        if(prefix.matches("(?s).*(?:是否|不一定|未必|不代表|没有证据|尚无证据|未识别到|未识别为|不能确认|无法判断|无法确定|尚未确认|无法确认|待核对|需核对)[^。！？；，,]{0,12}$"))return Stance.UNKNOWN;
        if(prefix.matches("(?s).*(?:不过于|以免|避免|防止|不要|减少|降低)[^。！？；，,]{0,6}$"))return Stance.PREVENTION;
        if(ABSENT_STATE.matcher(suffix).matches() || prefix.matches("(?s).*(?:不是|并非|非|没有|无|未经过|未涉及|未采用|未使用|未|不)(?:任何|明显|经过|采用|的|是|属于){0,3}$"))return Stance.NEGATIVE;
        if(review && !prefix.matches("(?s).*(?:已确认|已经|已有的|确实|明显|存在|经过|属于)(?:的)?$"))return Stance.REVIEW;
        if(prefix.matches("(?s)^(?:以便|为了|以)(?:更|更加)?(?:好地|准确地|精准地)?(?:评估|控制)[^。！？；，,]{0,6}$"))return Stance.PREVENTION;
        return Stance.POSITIVE;
    }

    private static String compact(String value) {
        return value.replaceAll("[\\s“”‘’\"'：:=（）()]", "");
    }

    private static boolean reviewRequest(String text) {
        var action = REVIEW.matcher(text);
        while (action.find()) {
            String before = text.substring(0, action.start()).strip();
            if (before.matches("(?s).*(?:待|未|无法|尚未)$")) continue;
            if (before.isEmpty() || before.matches("(?s).*(?:请|建议|需要|应当|务必|需|请您|以|以便|为了).{0,4}$")) return true;
        }
        return false;
    }

    private static List<Mention> foodMentions(String text, List<FoodItem> foods) {
        var result = new ArrayList<Mention>();
        for (var food : foods) {
            if (food.getName() == null || food.getName().isBlank()) continue;
            for (int start = text.indexOf(food.getName()); start >= 0; start = text.indexOf(food.getName(), start + food.getName().length()))
                result.add(new Mention(food, start, start + food.getName().length()));
        }
        result.sort(Comparator.comparingInt(Mention::start));
        return result;
    }

    private static List<FoodItem> subjects(String text, RiskMention risk, List<Mention> mentions) {
        for (var mention : mentions) if (mention.start() >= risk.end()
                && text.substring(risk.end(), mention.start()).matches("(?:的|过的|制的|\\s)*")) return List.of(mention.food());
        return precedingGroup(text, mentions, risk.start());
    }

    private static List<FoodItem> precedingGroup(String text, List<Mention> mentions, int position) {
        var group = new LinkedHashSet<FoodItem>();
        int next = -1;
        for (int i = mentions.size() - 1; i >= 0; i--) {
            var mention = mentions.get(i);
            if (mention.end() > position) continue;
            if (next >= 0 && !text.substring(mention.end(), next).replaceAll("以及|和|与|及|、|的|等|均|都|\\s", "").isEmpty()) break;
            group.add(mention.food()); next = mention.start();
        }
        return List.copyOf(group);
    }
}
