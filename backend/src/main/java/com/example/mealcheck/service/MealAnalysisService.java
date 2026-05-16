package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.MealAnalysisResponse;
import com.example.mealcheck.dto.MealRecordPageResponse;
import com.example.mealcheck.dto.MealRecordResponse;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.dto.StructureEvaluation;
import com.example.mealcheck.dto.UserMealTrendResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class MealAnalysisService {

    private final UserAccountRepository userRepository;
    private final MealRecordRepository mealRecordRepository;
    private final VisionFoodRecognitionService recognitionService;
    private final MealScoringService scoringService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final AdviceGenerationService adviceGenerationService;
    private final ImageStorageService imageStorageService;
    private final UserDietProfileService userDietProfileService;
    private final ObjectMapper objectMapper;

    public MealAnalysisService(UserAccountRepository userRepository,
                               MealRecordRepository mealRecordRepository,
                               VisionFoodRecognitionService recognitionService,
                               MealScoringService scoringService,
                               KnowledgeIndexService knowledgeIndexService,
                               AdviceGenerationService adviceGenerationService,
                               ImageStorageService imageStorageService,
                               UserDietProfileService userDietProfileService,
                               ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.recognitionService = recognitionService;
        this.scoringService = scoringService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.adviceGenerationService = adviceGenerationService;
        this.imageStorageService = imageStorageService;
        this.userDietProfileService = userDietProfileService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MealAnalysisResponse analyze(UserPrincipal principal, MultipartFile image, String goal) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();

        String normalizedGoal = goal == null || goal.isBlank() ? "balanced" : goal;
        String imagePath = imageStorageService.store(user.getId(), image);

        RecognitionResult recognition = recognitionService.recognize(image);
        StructureEvaluation evaluation = scoringService.evaluate(recognition, normalizedGoal);

        String query = buildRagQuery(recognition, evaluation, normalizedGoal);
        List<KnowledgeSnippet> snippets = filterRelevantSnippets(
                knowledgeIndexService.search(query, 10),
                recognition,
                evaluation,
                normalizedGoal
        ).stream().limit(4).collect(Collectors.toList());

        String advice = adviceGenerationService.generate(recognition, evaluation, snippets, normalizedGoal);

        MealRecord record = new MealRecord();
        record.setUser(user);
        record.setOriginalFileName(image.getOriginalFilename());
        record.setStoredImagePath(imagePath);
        record.setGoal(normalizedGoal);
        record.setScore(evaluation.getScore());
        record.setSummary(evaluation.getSummary());
        record.setDetectedFoodsJson(Jsons.toJson(objectMapper, recognition.getFoods()));
        record.setCategoryCountsJson(Jsons.toJson(objectMapper, evaluation.getCategoryCounts()));
        record.setRiskTagsJson(Jsons.toJson(objectMapper, evaluation.getRiskTags()));
        record.setAdvice(advice);
        mealRecordRepository.save(record);

        user.setLastUploadAt(LocalDateTime.now());
        userRepository.save(user);
        userDietProfileService.refresh(user);

        MealAnalysisResponse response = new MealAnalysisResponse();
        response.setRecordId(record.getId());
        response.setCreatedAt(record.getCreatedAt());
        response.setRecognition(recognition);
        response.setEvaluation(evaluation);
        response.setReferences(snippets);
        response.setAdvice(advice);

        return response;
    }

    @Transactional(readOnly = true)
    public List<MealRecordResponse> listRecent(UserPrincipal principal) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();

        return mealRecordRepository.findTop30ByUserOrderByCreatedAtDesc(user).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public MealRecordPageResponse listRecentPage(UserPrincipal principal,
                                                 int page,
                                                 int size,
                                                 String goal,
                                                 LocalDate from,
                                                 LocalDate to,
                                                 Integer minScore,
                                                 Integer maxScore) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));

        Page<MealRecord> records = mealRecordRepository.findAll(
                userMealSpecification(user, goal, from, to, minScore, maxScore),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        return new MealRecordPageResponse(
                records.getContent().stream().map(this::toResponse).toList(),
                records.getTotalElements(),
                records.getTotalPages(),
                records.getNumber(),
                records.getSize()
        );
    }

    private Specification<MealRecord> userMealSpecification(UserAccount user,
                                                            String goal,
                                                            LocalDate from,
                                                            LocalDate to,
                                                            Integer minScore,
                                                            Integer maxScore) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user"), user));

            if (goal != null && !goal.isBlank() && !"all".equalsIgnoreCase(goal)) {
                predicates.add(cb.equal(root.get("goal"), goal.trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
            }
            if (minScore != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("score"), minScore));
            }
            if (maxScore != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("score"), maxScore));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public List<MealRecord> listSince(UserAccount user, int days) {
        return mealRecordRepository.findByUserAndCreatedAtAfterOrderByCreatedAtAsc(
                user,
                LocalDateTime.now().minusDays(days)
        );
    }

    @Transactional(readOnly = true)
    public UserMealTrendResponse trend(UserPrincipal principal, int days) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();
        int safeDays = Math.max(7, Math.min(days, 90));
        List<MealRecord> records = mealRecordRepository.findByUserAndCreatedAtAfterOrderByCreatedAtAsc(
                user,
                LocalDateTime.now().minusDays(safeDays)
        );

        List<Integer> scores = records.stream()
                .map(MealRecord::getScore)
                .filter(Objects::nonNull)
                .toList();
        int averageScore = scores.isEmpty()
                ? 0
                : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
        long lowScoreCount = scores.stream().filter(score -> score < 70).count();
        long highScoreCount = scores.stream().filter(score -> score >= 80).count();

        return new UserMealTrendResponse(
                safeDays,
                records.size(),
                averageScore,
                lowScoreCount,
                highScoreCount,
                scoreTrend(records),
                trendSuggestion(averageScore, lowScoreCount, records.size()),
                dailyAverageScores(records),
                topMetrics(records.stream().flatMap(record -> extractFoodNames(record).stream()).toList(), 8),
                topMetrics(records.stream().flatMap(record -> extractRiskTags(record).stream()).toList(), 8)
        );
    }

    @Transactional
    public void deleteMeal(UserPrincipal principal, Long id) {
        UserAccount user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));

        MealRecord record = mealRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));

        if (!record.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能删除其他用户的饮食记录");
        }

        String storedImagePath = record.getStoredImagePath();

        mealRecordRepository.delete(record);

        imageStorageService.deleteSafely(storedImagePath);
    }

    @Transactional(readOnly = true)
    public Path getMealImagePath(UserPrincipal principal, Long id) {
        UserAccount user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));

        MealRecord record = mealRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));

        if (!record.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能查看其他用户的饮食图片");
        }

        if (record.getStoredImagePath() == null || record.getStoredImagePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        }

        return imageStorageService.resolve(record.getStoredImagePath());
    }

    public MealRecordResponse toResponse(MealRecord record) {
        MealRecordResponse response = new MealRecordResponse();

        response.setId(record.getId());
        response.setCreatedAt(record.getCreatedAt());
        response.setGoal(record.getGoal());
        response.setScore(record.getScore());
        response.setSummary(record.getSummary());
        response.setImageUrl("/api/meals/" + record.getId() + "/image");

        response.setFoods(Jsons.fromJson(
                objectMapper,
                record.getDetectedFoodsJson(),
                new TypeReference<java.util.List<com.example.mealcheck.dto.FoodItem>>() {}
        ));

        response.setCategoryCounts(Jsons.readStringIntegerMap(
                objectMapper,
                record.getCategoryCountsJson()
        ));

        response.setRiskTags(Jsons.fromJson(
                objectMapper,
                record.getRiskTagsJson(),
                new TypeReference<java.util.List<String>>() {}
        ));

        response.setAdvice(record.getAdvice());

        return response;
    }

    private List<String> extractFoodNames(MealRecord record) {
        List<FoodItem> foods = Jsons.fromJson(
                objectMapper,
                record.getDetectedFoodsJson(),
                new TypeReference<List<FoodItem>>() {}
        );

        if (foods == null) {
            return List.of();
        }

        return foods.stream()
                .map(FoodItem::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private List<String> extractRiskTags(MealRecord record) {
        List<String> tags = Jsons.fromJson(
                objectMapper,
                record.getRiskTagsJson(),
                new TypeReference<List<String>>() {}
        );

        if (tags == null) {
            return List.of();
        }

        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .toList();
    }

    private List<UserMealTrendResponse.MetricItem> topMetrics(List<String> values, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> counts.merge(value.trim(), 1, Integer::sum));

        return counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> new UserMealTrendResponse.MetricItem(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<UserMealTrendResponse.MetricItem> dailyAverageScores(List<MealRecord> records) {
        return records.stream()
                .filter(record -> record.getCreatedAt() != null && record.getScore() != null)
                .collect(Collectors.groupingBy(
                        record -> record.getCreatedAt().toLocalDate(),
                        LinkedHashMap::new,
                        Collectors.averagingInt(MealRecord::getScore)
                ))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new UserMealTrendResponse.MetricItem(
                        entry.getKey().toString(),
                        (int) Math.round(entry.getValue())
                ))
                .toList();
    }

    private String scoreTrend(List<MealRecord> records) {
        List<MealRecord> scored = records.stream()
                .filter(record -> record.getScore() != null)
                .sorted(Comparator.comparing(MealRecord::getCreatedAt))
                .toList();

        if (scored.size() < 2) {
            return "记录较少，趋势暂不明显";
        }

        int first = scored.get(0).getScore();
        int last = scored.get(scored.size() - 1).getScore();
        if (Math.abs(last - first) < 3) {
            return "整体评分比较稳定";
        }
        return last > first ? "最近评分有上升趋势" : "最近评分略有下降";
    }

    private String trendSuggestion(int averageScore, long lowScoreCount, int total) {
        if (total == 0) {
            return "还没有足够记录。可以先上传几餐，系统会逐步生成更可靠的趋势判断。";
        }
        if (averageScore >= 82 && lowScoreCount == 0) {
            return "整体表现不错，继续保持主食、蛋白质和蔬菜的稳定搭配。";
        }
        if (lowScoreCount >= Math.max(2, total / 3)) {
            return "低分记录占比偏高，建议优先减少油炸、高糖饮品和蔬菜不足的情况。";
        }
        if (averageScore < 75) {
            return "平均分还有提升空间，下一阶段先把每餐蛋白质和蔬菜补齐。";
        }
        return "整体处于可控范围，建议继续管理高油高糖频率，并保持规律记录。";
    }

    private String buildRagQuery(RecognitionResult recognition, StructureEvaluation evaluation, String goal) {
        String foods = recognition.getFoods().stream()
                .map(f -> f.getName() + " " + f.getCategory() + " " + (f.getNote() == null ? "" : f.getNote()))
                .collect(Collectors.joining(" "));

        StringBuilder query = new StringBuilder();
        query.append(foods).append(" ");
        query.append(String.join(" ", evaluation.getRiskTags())).append(" ");

        if ("fat_loss".equals(goal)) {
            query.append("减脂 饮食结构 主食 蛋白质 蔬菜 控油 ");
        } else if ("muscle_gain".equals(goal)) {
            query.append("增肌 蛋白质 主食 蔬菜 ");
        } else if ("light".equals(goal)) {
            query.append("清淡饮食 少油 少盐 蔬菜 汤品 ");
        } else {
            query.append("均衡饮食 主食 蛋白质 蔬菜 ");
        }

        return query.toString();
    }

    private List<KnowledgeSnippet> filterRelevantSnippets(
            List<KnowledgeSnippet> snippets,
            RecognitionResult recognition,
            StructureEvaluation evaluation,
            String goal
    ) {
        boolean hasSweetDrinkOrDessert = hasCategory(recognition, "drink")
                || hasCategory(recognition, "dessert")
                || containsFoodKeyword(
                recognition,
                List.of("可乐", "奶茶", "饮料", "果汁", "甜品", "蛋糕", "甜点")
        );

        boolean hasHighOilRisk = evaluation.getRiskTags().stream().anyMatch(tag -> tag.contains("高油"))
                || containsFoodKeyword(
                recognition,
                List.of("炸", "油炸", "红烧", "肥肉", "五花肉", "干锅", "糖醋", "酱汁", "重油")
        );

        boolean hasStaple = hasCategory(recognition, "staple");
        boolean hasProtein = hasCategory(recognition, "protein") || hasCategory(recognition, "dairy");
        boolean hasVegetable = hasCategory(recognition, "vegetable");

        return snippets.stream()
                .filter(snippet -> {
                    String title = snippet.getTitle() == null ? "" : snippet.getTitle();

                    if (title.contains("含糖饮料") || title.contains("甜品")) {
                        return hasSweetDrinkOrDessert;
                    }

                    if (title.contains("高油")) {
                        return hasHighOilRisk;
                    }

                    if (title.contains("减脂")) {
                        return "fat_loss".equals(goal);
                    }

                    if (title.contains("增肌")) {
                        return "muscle_gain".equals(goal);
                    }

                    if (title.contains("清淡")) {
                        return "light".equals(goal);
                    }

                    if (title.contains("主食")) {
                        return hasStaple;
                    }

                    if (title.contains("蛋白质")) {
                        return hasProtein;
                    }

                    if (title.contains("蔬菜")) {
                        return hasVegetable;
                    }

                    return true;
                })
                .collect(Collectors.toList());
    }

    private boolean hasCategory(RecognitionResult recognition, String category) {
        if (recognition == null || recognition.getFoods() == null) {
            return false;
        }

        return recognition.getFoods().stream()
                .anyMatch(food -> category.equalsIgnoreCase(food.getCategory()));
    }

    private boolean containsFoodKeyword(RecognitionResult recognition, List<String> keywords) {
        if (recognition == null || recognition.getFoods() == null) {
            return false;
        }

        return recognition.getFoods().stream().anyMatch(food -> {
            String name = food.getName() == null ? "" : food.getName();
            String note = food.getNote() == null ? "" : food.getNote();
            String text = name + " " + note;

            return keywords.stream().anyMatch(text::contains);
        });
    }

}
