package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.KnowledgeSnippet;
import com.example.mealcheck.dto.MealAnalysisResponse;
import com.example.mealcheck.dto.MealRecordResponse;
import com.example.mealcheck.dto.RecognitionResult;
import com.example.mealcheck.dto.StructureEvaluation;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MealAnalysisService {

    private final UserAccountRepository userRepository;
    private final MealRecordRepository mealRecordRepository;
    private final VisionFoodRecognitionService recognitionService;
    private final MealScoringService scoringService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final AdviceGenerationService adviceGenerationService;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public MealAnalysisService(UserAccountRepository userRepository,
                               MealRecordRepository mealRecordRepository,
                               VisionFoodRecognitionService recognitionService,
                               MealScoringService scoringService,
                               KnowledgeIndexService knowledgeIndexService,
                               AdviceGenerationService adviceGenerationService,
                               ObjectMapper objectMapper,
                               AppProperties properties) {
        this.userRepository = userRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.recognitionService = recognitionService;
        this.scoringService = scoringService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.adviceGenerationService = adviceGenerationService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public MealAnalysisResponse analyze(UserPrincipal principal, MultipartFile image, String goal) {
        UserAccount user = userRepository.findByUsername(principal.getUsername()).orElseThrow();

        String normalizedGoal = goal == null || goal.isBlank() ? "balanced" : goal;
        String imagePath = storeImage(user.getId(), image);

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
    public List<MealRecord> listSince(UserAccount user, int days) {
        return mealRecordRepository.findByUserAndCreatedAtAfterOrderByCreatedAtAsc(
                user,
                LocalDateTime.now().minusDays(days)
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

        deleteImageFileSafely(storedImagePath);
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

        return Path.of(record.getStoredImagePath());
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

    private String storeImage(Long userId, MultipartFile image) {
        try {
            Path dir = Path.of(properties.getUploadDir(), "user-" + userId);
            Files.createDirectories(dir);

            String ext = ".jpg";
            String original = image.getOriginalFilename();

            if (original != null && original.contains(".")) {
                ext = original.substring(original.lastIndexOf('.'))
                        .replaceAll("[^a-zA-Z0-9.]", "");
            }

            Path target = dir.resolve(UUID.randomUUID() + ext);
            Files.copy(image.getInputStream(), target);

            return target.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteImageFileSafely(String storedImagePath) {
        if (storedImagePath == null || storedImagePath.isBlank()) {
            return;
        }

        try {
            Path uploadRoot = Path.of(properties.getUploadDir())
                    .toAbsolutePath()
                    .normalize();

            Path imagePath = Path.of(storedImagePath)
                    .toAbsolutePath()
                    .normalize();

            if (!imagePath.startsWith(uploadRoot)) {
                System.err.println("拒绝删除 uploads 目录外的文件: " + imagePath);
                return;
            }

            if (Files.exists(imagePath) && Files.isRegularFile(imagePath)) {
                Files.deleteIfExists(imagePath);
            }
        } catch (Exception e) {
            System.err.println("删除图片文件失败: " + storedImagePath + "，原因: " + e.getMessage());
        }
    }
}