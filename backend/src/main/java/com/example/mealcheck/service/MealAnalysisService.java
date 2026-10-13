package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

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
    @org.springframework.beans.factory.annotation.Value("${mealcheck.ai.model:unknown}")
    private String recognitionModel = "unknown";

    private final BehaviorTrackingService tracking;

    private final UserAccountRepository userRepository;
    private final MealRecordRepository mealRecordRepository;
    private final VisionFoodRecognitionService recognitionService;
    private final MealScoringService scoringService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final AdviceGenerationService adviceGenerationService;
    private final ImageStorageService imageStorageService;
    private final ImageUploadValidationService imageUploadValidationService;
    private final NonFoodUploadGuardService nonFoodUploadGuardService;
    private final UserGoalService userGoalService;
    private final MealRecordPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final com.example.mealcheck.config.AppProperties properties;

    public MealAnalysisService(UserAccountRepository userRepository,
                               MealRecordRepository mealRecordRepository,
                               VisionFoodRecognitionService recognitionService,
                               MealScoringService scoringService,
                               KnowledgeIndexService knowledgeIndexService,
                               AdviceGenerationService adviceGenerationService,
                               ImageStorageService imageStorageService,
                               ImageUploadValidationService imageUploadValidationService,
                               NonFoodUploadGuardService nonFoodUploadGuardService,
                               UserGoalService userGoalService,
                               MealRecordPersistenceService persistenceService,
                               ObjectMapper objectMapper,
                               com.example.mealcheck.config.AppProperties properties, BehaviorTrackingService tracking) {
        this.tracking = tracking;
        this.userRepository = userRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.recognitionService = recognitionService;
        this.scoringService = scoringService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.adviceGenerationService = adviceGenerationService;
        this.imageStorageService = imageStorageService;
        this.imageUploadValidationService = imageUploadValidationService;
        this.nonFoodUploadGuardService = nonFoodUploadGuardService;
        this.userGoalService = userGoalService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MealAnalysisResponse analyze(UserPrincipal principal, MultipartFile image, String goal) {
        try (var scope = analysisDeadline(principal)) {
            PreparedMeal prepared = prepare(principal, image, goal);
            AiCallContext.checkExecution();
            persistenceService.saveWithImage(prepared.user(), prepared.record(), prepared.image());
            return prepared.response();
        }
    }

    PreparedMeal prepare(UserPrincipal principal, MultipartFile image, String goal) {
        try (var scope = analysisDeadline(principal)) {
            AiCallContext.checkExecution();
            var validated = imageUploadValidationService.validateAndNormalize(image);
            AiCallContext.checkExecution();
            return prepareValidated(principal, validated, goal);
        }
    }

    AiCallContext.Scope analysisDeadline(UserPrincipal principal) {
        return AiCallContext.open(principal.getId(), java.time.Duration.ofSeconds(
                Math.max(1, properties.getAiBudget().getOperationTimeoutSeconds())));
    }

    PreparedMeal prepareValidated(UserPrincipal principal, ValidatedImage validatedImage, String goal) {
        try (var scope=AiCallContext.open(principal.getId(),java.time.Duration.ofSeconds(Math.max(1,properties.getAiBudget().getOperationTimeoutSeconds())))) {
            return prepareValidatedWithinDeadline(principal,validatedImage,goal);
        }
    }

    private PreparedMeal prepareValidatedWithinDeadline(UserPrincipal principal, ValidatedImage validatedImage, String goal) {
        AiCallContext.checkExecution();
        UserAccount user = userRepository.findById(principal.getId()).orElseThrow();
        if (nonFoodUploadGuardService.isBlocked(user.getUsername())) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "因连续上传非饮食图片，已临时限制上传。剩余 "
                            + nonFoodUploadGuardService.remainingBlockMinutes(user.getUsername()) + " 分钟后可再试。"
            );
        }

        AiCallContext.checkExecution();
        RecognitionResult recognition = recognitionService.recognize(validatedImage);
        AiCallContext.checkExecution();
        if (!recognition.isFoodImage()) {
            String reason = recognition.getRejectionReason() == null || recognition.getRejectionReason().isBlank()
                    ? "上传的图片不是饮食图片，请上传包含餐食的照片。"
                    : recognition.getRejectionReason();
            NonFoodUploadGuardService.NonFoodUploadDecision decision = nonFoodUploadGuardService.register(user, reason);

            if (decision.thresholdReached()) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "因连续上传非饮食图片，已临时限制上传。系统已向管理员发出预警。剩余 "
                                + nonFoodUploadGuardService.remainingBlockMinutes(user.getUsername()) + " 分钟后可再试。"
                );
            }

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        String normalizedGoal = userGoalService.effectiveGoal(user, goal);
        String originalFoods = Jsons.toJson(objectMapper, recognition.getFoods());
        // Keep persisted foods and advice consistent with the facts used by scoring.
        recognition.setFoods(scoringService.acceptedFoods(recognition));
        StructureEvaluation evaluation = scoringService.evaluate(recognition, normalizedGoal);

        String query = buildRagQuery(recognition, evaluation, normalizedGoal);
        AiCallContext.checkExecution();
        List<KnowledgeSnippet> snippets = filterRelevantSnippets(
                knowledgeIndexService.search(query, 10),
                recognition,
                evaluation,
                normalizedGoal
        ).stream().limit(4).collect(Collectors.toList());

        AiCallContext.checkExecution();
        String advice = adviceGenerationService.generate(recognition, evaluation, snippets, normalizedGoal);
        AiCallContext.checkExecution();

        MealRecord record = new MealRecord();
        record.setUser(user);
        record.setOriginalFileName(validatedImage.originalFilename());
        record.setGoal(normalizedGoal);
        record.setScore(evaluation.getScore());
        record.setSummary(evaluation.getSummary());
        record.setDetectedFoodsJson(Jsons.toJson(objectMapper, recognition.getFoods()));
        record.setCategoryCountsJson(Jsons.toJson(objectMapper, evaluation.getCategoryCounts()));
        record.setRiskTagsJson(Jsons.toJson(objectMapper, evaluation.getRiskTags()));
        record.setAdvice(advice);
        record.setOriginalFoodsJson(originalFoods);
        record.setScoringVersion(MealScoringService.VERSION);
        record.setRecognitionModel(recognitionModel);

        MealAnalysisResponse response = new MealAnalysisResponse();
        response.setCreatedAt(record.getCreatedAt());
        response.setOccurredAt(record.getOccurredAt()); response.setMealType(record.getMealType());
        response.setRecordSource(record.getRecordSource()); response.setTimeConfirmed(record.isTimeConfirmed());
        response.setMetadataVersion(record.getMetadataVersion()); response.setNote(record.getNote());
        response.setRecognition(recognition);
        response.setEvaluation(evaluation);
        response.setReferences(snippets);
        response.setAdvice(advice);
        response.setScoringVersion(record.getScoringVersion());
        return new PreparedMeal(user, record, validatedImage, response);
    }

    record PreparedMeal(UserAccount user, MealRecord record, ValidatedImage image, MealAnalysisResponse draft) {
        MealAnalysisResponse response() {
            draft.setRecordId(record.getId());
            return draft;
        }
    }

    @Transactional(readOnly = true)
    public List<MealRecordResponse> listRecent(UserPrincipal principal) {
        UserAccount user = userRepository.findById(principal.getId()).orElseThrow();

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
        UserAccount user = userRepository.findById(principal.getId()).orElseThrow();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));

        Page<MealRecord> records = mealRecordRepository.findAll(
                userMealSpecification(user, goal, from, to, minScore, maxScore),
                PageRequest.of(safePage, safeSize, Sort.unsorted())
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
            var mealTime = cb.<LocalDateTime>coalesce(root.get("occurredAt"),root.get("createdAt"));
            if (query.getResultType()!=Long.class && query.getResultType()!=long.class) query.orderBy(cb.desc(mealTime),cb.desc(root.get("id")));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(mealTime, AppTime.startOfDay(from)));
            }
            if (to != null) {
                predicates.add(cb.lessThan(mealTime, AppTime.startOfDay(to.plusDays(1))));
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
                AppTime.now().minusDays(days)
        );
    }

    @Transactional(readOnly = true)
    public List<MealRecord> listPeriod(UserAccount user, LocalDate start, LocalDate end) {
        return mealRecordRepository.findByUserAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                user, AppTime.startOfDay(start), AppTime.startOfDay(end.plusDays(1)));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public UserMealTrendResponse trend(UserPrincipal principal, int days) {
        long userId = principal.getId();
        int safeDays = Math.max(7, Math.min(days, 90));
        LocalDateTime since = AppTime.startOfDay(AppTime.today().minusDays(safeDays-1L));
        var summary = mealRecordRepository.trendSummary(userId, since);
        int averageScore = summary.getAverage() == null ? 0 : (int) Math.round(summary.getAverage());
        List<String> foods = new ArrayList<>(), risks = new ArrayList<>();
        for (var fact : mealRecordRepository.trendFacts(userId, since)) {
            List<FoodItem> parsedFoods = Jsons.fromJson(objectMapper, fact.getFoods(), new TypeReference<List<FoodItem>>() {});
            if (parsedFoods != null) parsedFoods.stream().filter(Objects::nonNull).map(FoodItem::getName).forEach(foods::add);
            List<String> parsedRisks = Jsons.fromJson(objectMapper, fact.getRisks(), new TypeReference<List<String>>() {});
            if (parsedRisks != null) risks.addAll(parsedRisks);
        }
        var daily = mealRecordRepository.trendDailyScores(userId, since, AppTime.businessZone().getId()).stream()
                .map(row -> new UserMealTrendResponse.MetricItem(row.getDay().toString(), (int) Math.round(row.getAverage())))
                .toList();

        var response = new UserMealTrendResponse(
                safeDays,
                summary.getTotal(),
                averageScore,
                summary.getLow(),
                summary.getHigh(),
                scoreTrend(summary),
                trendSuggestion(averageScore, summary.getLow(), summary.getTotal()),
                daily,
                topMetrics(foods, 8),
                topMetrics(risks, 8)
        );
        if (tracking!=null) response.setTracking(tracking.summary(userId,AppTime.today().minusDays(safeDays-1L),AppTime.today()));
        return response;
    }

    @Transactional
    public void deleteMeal(UserPrincipal principal, Long id) {
        UserAccount user = userRepository.findByIdForUpdate(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));

        MealRecord record = mealRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));

        if (!record.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能删除其他用户的饮食记录");
        }

        String storedImagePath = record.getStoredImagePath();

        persistenceService.delete(record);

        imageStorageService.deleteAfterCommit(storedImagePath);
    }

    @Transactional(readOnly = true)
    public Path getMealImagePath(UserPrincipal principal, Long id) {
        return imageStorageService.resolve(authorizedImageReference(principal,id));
    }
    @Transactional(readOnly = true)
    public org.springframework.core.io.Resource getMealImageResource(UserPrincipal principal, Long id) {
        return imageStorageService.resource(authorizedImageReference(principal,id));
    }
    private String authorizedImageReference(UserPrincipal principal,Long id) {
        UserAccount user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));

        MealRecord record = mealRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));

        if (!record.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能查看其他用户的饮食图片");
        }

        if (record.getStoredImagePath() == null || record.getStoredImagePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        }

        return record.getStoredImagePath();
    }

    public MealRecordResponse toResponse(MealRecord record) {
        MealRecordResponse response = new MealRecordResponse();

        response.setId(record.getId());
        response.setCreatedAt(record.getCreatedAt());
        response.setOccurredAt(record.getOccurredAt()); response.setMealType(record.getMealType());
        response.setRecordSource(record.getRecordSource()); response.setTimeConfirmed(record.isTimeConfirmed());
        response.setMetadataVersion(record.getMetadataVersion()); response.setNote(record.getNote());
        response.setGoal(record.getGoal());
        response.setScore(record.getScore());
        response.setSummary(record.getSummary());
        response.setImageUrl(record.getStoredImagePath()==null ? null : "/api/meals/" + record.getId() + "/image");

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
        response.setRevision(record.getRevision());
        response.setScoringVersion(record.getScoringVersion());

        return response;
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

    private String scoreTrend(MealRecordRepository.TrendSummary summary) {
        if (summary.getScored() < 2) return "记录较少，趋势暂不明显";
        int first = summary.getFirstScore(), last = summary.getLastScore();
        if (Math.abs(last - first) < 3) return "整体评分比较稳定";
        return last > first ? "最近评分有上升趋势" : "最近评分略有下降";
    }

    private String trendSuggestion(int averageScore, long lowScoreCount, long total) {
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
        boolean hasSweetDrinkOrDessert = evaluation.getRiskTags().stream()
                .anyMatch(tag -> tag.contains("含糖") || tag.contains("甜品"));
        boolean hasHighOilRisk = evaluation.getRiskTags().stream()
                .anyMatch(tag -> tag.contains("高油") || tag.contains("油炸"));

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

}
