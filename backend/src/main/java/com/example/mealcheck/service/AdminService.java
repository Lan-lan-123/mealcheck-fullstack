package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminAuditLogPageResponse;
import com.example.mealcheck.dto.AdminDashboardResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkPageResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkRequest;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import com.example.mealcheck.dto.AdminMealAnalyticsResponse;
import com.example.mealcheck.dto.AdminMealPageResponse;
import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.dto.AdminStatsResponse;
import com.example.mealcheck.dto.AdminSystemResponse;
import com.example.mealcheck.dto.AdminUserPageResponse;
import com.example.mealcheck.dto.AdminUserResponse;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AdminService {
    private static final String ADMIN_STATS_CACHE_KEY = "admin:stats";
    private static final java.time.Duration ADMIN_STATS_TTL = java.time.Duration.ofSeconds(60);

    private final UserAccountRepository userAccountRepository;
    private final MealRecordRepository mealRecordRepository;
    private final KnowledgeIndexService knowledgeIndexService;
    private final PgVectorKnowledgeService pgVectorKnowledgeService;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;
    private final AiStatusService aiStatusService;
    private final RedisCacheService redisCacheService;
    private final AdminAuditService adminAuditService;

    @Value("${mealcheck.ai.api-key:}")
    private String apiKey;

    @Value("${mealcheck.ai.base-url:}")
    private String baseUrl;

    @Value("${mealcheck.ai.model:unknown}")
    private String aiModel;

    public AdminService(UserAccountRepository userAccountRepository,
                        MealRecordRepository mealRecordRepository,
                        KnowledgeIndexService knowledgeIndexService,
                        PgVectorKnowledgeService pgVectorKnowledgeService,
                        ImageStorageService imageStorageService,
                        ObjectMapper objectMapper,
                        AiStatusService aiStatusService,
                        RedisCacheService redisCacheService,
                        AdminAuditService adminAuditService) {
        this.userAccountRepository = userAccountRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.knowledgeIndexService = knowledgeIndexService;
        this.pgVectorKnowledgeService = pgVectorKnowledgeService;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
        this.aiStatusService = aiStatusService;
        this.redisCacheService = redisCacheService;
        this.adminAuditService = adminAuditService;
    }

    public AdminStatsResponse stats() {
        return redisCacheService.getJson(ADMIN_STATS_CACHE_KEY, new TypeReference<AdminStatsResponse>() {})
                .orElseGet(() -> {
                    AdminStatsResponse stats = buildStats();
                    redisCacheService.setJson(ADMIN_STATS_CACHE_KEY, stats, ADMIN_STATS_TTL);
                    return stats;
                });
    }

    private AdminStatsResponse buildStats() {
        long userCount = userAccountRepository.count();
        long mealRecordCount = mealRecordRepository.count();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayMealRecordCount = mealRecordRepository.countByCreatedAtAfter(todayStart);

        long knowledgeChunkCount = knowledgeIndexService.countChunks();

        return new AdminStatsResponse(
                userCount,
                mealRecordCount,
                todayMealRecordCount,
                knowledgeChunkCount
        );
    }

    public AdminDashboardResponse dashboard() {
        AdminStatsResponse overview = stats();

        AdminSystemResponse system = new AdminSystemResponse(
                "Spring Boot",
                "PostgreSQL",
                "pgvector",
                "HashEmbeddingService / Feature Hashing",
                384,
                aiModel,
                apiKey != null && !apiKey.isBlank(),
                baseUrl != null && !baseUrl.isBlank(),
                "resources/knowledge/diet_guides.md",
                aiStatusService.recentCalls()
        );

        return new AdminDashboardResponse(overview, system);
    }

    public List<AdminUserResponse> users() {
        return userAccountRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse users(int page, int size, String username, String role) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<UserAccount> users = userAccountRepository.findAll(userSpecification(username, role), pageable);
        return new AdminUserPageResponse(
                users.getContent().stream().map(this::toUserResponse).toList(),
                users.getTotalElements(),
                users.getTotalPages(),
                users.getNumber(),
                users.getSize()
        );
    }

    @Transactional(readOnly = true)
    public AdminMealPageResponse meals(int page,
                                       int size,
                                       String username,
                                       LocalDate from,
                                       LocalDate to,
                                       Integer minScore,
                                       Integer maxScore) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<MealRecord> records = mealRecordRepository.findAll(
                mealSpecification(username, from, to, minScore, maxScore),
                pageable
        );

        return new AdminMealPageResponse(
                records.getContent().stream().map(this::toMealResponse).toList(),
                records.getTotalElements(),
                records.getTotalPages(),
                records.getNumber(),
                records.getSize()
        );
    }

    @Transactional(readOnly = true)
    public AdminMealAnalyticsResponse mealAnalytics(String username,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    Integer minScore,
                                                    Integer maxScore) {
        List<MealRecord> records = mealRecordRepository.findAll(
                mealSpecification(username, from, to, minScore, maxScore),
                Sort.by(Sort.Direction.ASC, "createdAt")
        );
        List<Integer> scores = records.stream()
                .map(MealRecord::getScore)
                .filter(Objects::nonNull)
                .toList();
        int averageScore = scores.isEmpty()
                ? 0
                : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));

        return new AdminMealAnalyticsResponse(
                records.size(),
                averageScore,
                List.of(
                        new AdminMealAnalyticsResponse.MetricItem("80 分及以上", (int) scores.stream().filter(score -> score >= 80).count()),
                        new AdminMealAnalyticsResponse.MetricItem("60-79 分", (int) scores.stream().filter(score -> score >= 60 && score < 80).count()),
                        new AdminMealAnalyticsResponse.MetricItem("60 分以下", (int) scores.stream().filter(score -> score < 60).count())
                ),
                topMetrics(records.stream()
                        .map(record -> record.getUser() == null ? "未知用户" : record.getUser().getUsername())
                        .toList(), 8),
                topMetrics(records.stream()
                        .flatMap(record -> extractFoodNames(record).stream())
                        .toList(), 10),
                topMetrics(records.stream()
                        .flatMap(record -> extractRiskTags(record).stream())
                        .toList(), 10),
                dailyAverageScores(records)
        );
    }

    public AdminKnowledgeChunkPageResponse knowledgeChunks(String keyword, String source, String sort, int page, int size) {
        return pgVectorKnowledgeService.listAdminChunks(keyword, source, sort, page, size);
    }

    public AdminAuditLogPageResponse auditLogs(int page, int size) {
        return adminAuditService.list(page, size);
    }

    public Object reindexKnowledge() {
        Object result = knowledgeIndexService.reindex();
        clearAdminCaches();
        return result;
    }

    public void addKnowledgeChunk(AdminKnowledgeChunkRequest request, UserPrincipal admin) {
        pgVectorKnowledgeService.add(
                "admin",
                request.getTitle().trim(),
                request.getContent().trim()
        );
        knowledgeIndexService.clearSearchCache();
        clearAdminCaches();
        adminAuditService.log(admin, "ADD_KNOWLEDGE", "knowledge_chunk", null, request.getTitle());
    }

    public void deleteKnowledgeChunk(Long id, UserPrincipal admin) {
        boolean deleted = pgVectorKnowledgeService.delete(id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG 知识片段不存在");
        }
        knowledgeIndexService.clearSearchCache();
        clearAdminCaches();
        adminAuditService.log(admin, "DELETE_KNOWLEDGE", "knowledge_chunk", id, "delete knowledge chunk");
    }

    public void updateKnowledgeChunk(Long id, AdminKnowledgeChunkRequest request, UserPrincipal admin) {
        boolean updated = pgVectorKnowledgeService.update(
                id,
                request.getTitle().trim(),
                request.getContent().trim()
        );
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG 知识片段不存在");
        }
        knowledgeIndexService.clearSearchCache();
        clearAdminCaches();
        adminAuditService.log(admin, "UPDATE_KNOWLEDGE", "knowledge_chunk", id, request.getTitle());
    }

    @Transactional
    public void deleteMealRecord(Long id, UserPrincipal admin) {
        MealRecord record = mealRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));
        String imagePath = record.getStoredImagePath();
        mealRecordRepository.delete(record);
        imageStorageService.deleteSafely(imagePath);
        clearAdminCaches();
        adminAuditService.log(admin, "DELETE_MEAL", "meal_record", id, "delete meal record");
    }

    @Transactional
    public void deleteUser(Long userId, UserPrincipal currentAdmin) {
        if (currentAdmin != null && Objects.equals(userId, currentAdmin.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除当前登录的管理员账号");
        }

        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));

        List<MealRecord> records = mealRecordRepository.findByUser(user);
        List<String> imagePaths = records.stream()
                .map(MealRecord::getStoredImagePath)
                .filter(path -> path != null && !path.isBlank())
                .toList();

        mealRecordRepository.deleteAll(records);
        userAccountRepository.delete(user);
        imagePaths.forEach(imageStorageService::deleteSafely);
        clearAdminCaches();
        adminAuditService.log(currentAdmin, "DELETE_USER", "user", userId, "delete user " + user.getUsername());
    }

    private void clearAdminCaches() {
        redisCacheService.delete(ADMIN_STATS_CACHE_KEY);
    }

    private AdminUserResponse toUserResponse(UserAccount user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getCreatedAt(),
                user.getLastUploadAt()
        );
    }

    private AdminMealRecordResponse toMealResponse(MealRecord record) {
        String username = record.getUser() == null ? "未知用户" : record.getUser().getUsername();

        List<FoodItem> foods = Jsons.fromJson(
                objectMapper,
                record.getDetectedFoodsJson(),
                new TypeReference<List<FoodItem>>() {}
        );

        List<String> foodNames = foods == null
                ? List.of()
                : foods.stream()
                .map(FoodItem::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        return new AdminMealRecordResponse(
                record.getId(),
                username,
                record.getScore(),
                record.getSummary(),
                foodNames,
                record.getCreatedAt()
        );
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

    private List<AdminMealAnalyticsResponse.MetricItem> topMetrics(List<String> values, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> counts.merge(value.trim(), 1, Integer::sum));

        return counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> new AdminMealAnalyticsResponse.MetricItem(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<AdminMealAnalyticsResponse.MetricItem> dailyAverageScores(List<MealRecord> records) {
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
                .limit(14)
                .map(entry -> new AdminMealAnalyticsResponse.MetricItem(
                        entry.getKey().toString(),
                        (int) Math.round(entry.getValue())
                ))
                .toList();
    }

    private Specification<UserAccount> userSpecification(String username, String role) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (username != null && !username.isBlank()) {
                String keyword = "%" + username.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), keyword),
                        cb.like(cb.lower(root.get("displayName")), keyword)
                ));
            }

            if (role != null && !role.isBlank() && !"ALL".equalsIgnoreCase(role)) {
                predicates.add(cb.equal(cb.upper(root.get("role")), role.trim().toUpperCase()));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private Specification<MealRecord> mealSpecification(String username,
                                                        LocalDate from,
                                                        LocalDate to,
                                                        Integer minScore,
                                                        Integer maxScore) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("user", JoinType.LEFT);
                query.distinct(true);
            }

            if (username != null && !username.isBlank()) {
                Join<MealRecord, UserAccount> user = root.join("user", JoinType.LEFT);
                predicates.add(cb.like(cb.lower(user.get("username")), "%" + username.trim().toLowerCase() + "%"));
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

}
