package com.example.mealcheck.service;

import com.example.mealcheck.dto.AdminAuditLogPageResponse;
import com.example.mealcheck.dto.AdminAssistantStatsResponse;
import com.example.mealcheck.dto.AdminDashboardResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkPageResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkRequest;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import com.example.mealcheck.dto.AdminMealAnalyticsResponse;
import com.example.mealcheck.dto.AdminMealPageResponse;
import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.dto.AdminNonFoodUploadEventPageResponse;
import com.example.mealcheck.dto.AdminNonFoodUploadEventResponse;
import com.example.mealcheck.dto.AdminOperationsResponse;
import com.example.mealcheck.dto.AdminStatsResponse;
import com.example.mealcheck.dto.AdminSystemResponse;
import com.example.mealcheck.dto.AdminUploadTrendResponse;
import com.example.mealcheck.dto.AdminUserPageResponse;
import com.example.mealcheck.dto.AdminUserResponse;
import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.RagEvaluationSummaryResponse;
import com.example.mealcheck.dto.RagBenchmarkResponse;
import com.example.mealcheck.entity.AssistantConversationMessage;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.NonFoodUploadEvent;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.NonFoodUploadEventRepository;
import com.example.mealcheck.repository.AssistantConversationMessageRepository;
import com.example.mealcheck.repository.AssistantConversationRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Set;

@Service
public class AdminService {
    private static final String ADMIN_STATS_CACHE_KEY = "admin:stats";
    private static final String ADMIN_STATS_STALE_CACHE_KEY = "admin:stats:stale";
    private static final java.time.Duration ADMIN_STATS_TTL = java.time.Duration.ofSeconds(60);
    private static final java.time.Duration ADMIN_STATS_TTL_JITTER = java.time.Duration.ofSeconds(15);
    private static final java.time.Duration ADMIN_STATS_STALE_TTL = java.time.Duration.ofMinutes(5);

    private final UserAccountRepository userAccountRepository;
    private final MealRecordRepository mealRecordRepository;
    private final NonFoodUploadEventRepository nonFoodUploadEventRepository;
    private final AssistantConversationRepository assistantConversationRepository;
    private final AssistantConversationMessageRepository assistantConversationMessageRepository;
    private final KnowledgeIndexService knowledgeIndexService;
    private final PgVectorKnowledgeService pgVectorKnowledgeService;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;
    private final AiStatusService aiStatusService;
    private final RedisCacheService redisCacheService;
    private final AdminAuditService adminAuditService;
    private final NonFoodUploadGuardService nonFoodUploadGuardService;
    private final RagEvaluationService ragEvaluationService;
    private final RagBenchmarkService ragBenchmarkService;
    private final AssistantProactiveAdviceService assistantProactiveAdviceService;
    private final AdminAnalyticsQueryService analyticsQueryService;
    private final MealRecordPersistenceService mealRecordPersistenceService;

    @Value("${mealcheck.ai.api-key:}")
    private String apiKey;

    @Value("${mealcheck.ai.base-url:}")
    private String baseUrl;

    @Value("${mealcheck.ai.model:unknown}")
    private String aiModel;

    public AdminService(UserAccountRepository userAccountRepository,
                        MealRecordRepository mealRecordRepository,
                        NonFoodUploadEventRepository nonFoodUploadEventRepository,
                        AssistantConversationRepository assistantConversationRepository,
                        AssistantConversationMessageRepository assistantConversationMessageRepository,
                        KnowledgeIndexService knowledgeIndexService,
                        PgVectorKnowledgeService pgVectorKnowledgeService,
                        ImageStorageService imageStorageService,
                        ObjectMapper objectMapper,
                        AiStatusService aiStatusService,
                        RedisCacheService redisCacheService,
                        AdminAuditService adminAuditService,
                        NonFoodUploadGuardService nonFoodUploadGuardService,
                        RagEvaluationService ragEvaluationService,
                        RagBenchmarkService ragBenchmarkService,
                        AssistantProactiveAdviceService assistantProactiveAdviceService,
                        AdminAnalyticsQueryService analyticsQueryService,
                        MealRecordPersistenceService mealRecordPersistenceService) {
        this.userAccountRepository = userAccountRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.nonFoodUploadEventRepository = nonFoodUploadEventRepository;
        this.assistantConversationRepository = assistantConversationRepository;
        this.assistantConversationMessageRepository = assistantConversationMessageRepository;
        this.knowledgeIndexService = knowledgeIndexService;
        this.pgVectorKnowledgeService = pgVectorKnowledgeService;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
        this.aiStatusService = aiStatusService;
        this.redisCacheService = redisCacheService;
        this.adminAuditService = adminAuditService;
        this.nonFoodUploadGuardService = nonFoodUploadGuardService;
        this.ragEvaluationService = ragEvaluationService;
        this.ragBenchmarkService = ragBenchmarkService;
        this.assistantProactiveAdviceService = assistantProactiveAdviceService;
        this.analyticsQueryService = analyticsQueryService;
        this.mealRecordPersistenceService = mealRecordPersistenceService;
    }

    public AdminStatsResponse stats() {
        java.util.Optional<AdminStatsResponse> cached = redisCacheService.getJson(
                ADMIN_STATS_CACHE_KEY, new TypeReference<AdminStatsResponse>() {});
        if (cached.isPresent()) {
            return cached.get();
        }
        java.util.Optional<AdminStatsResponse> stale = redisCacheService.getJson(
                ADMIN_STATS_STALE_CACHE_KEY, new TypeReference<AdminStatsResponse>() {});
        return redisCacheService.singleFlight(
                "load:" + ADMIN_STATS_CACHE_KEY,
                () -> redisCacheService.getJson(
                                ADMIN_STATS_CACHE_KEY, new TypeReference<AdminStatsResponse>() {})
                        .orElseGet(() -> {
                    AdminStatsResponse stats = buildStats();
                    redisCacheService.setJsonWithJitter(
                            ADMIN_STATS_CACHE_KEY, stats, ADMIN_STATS_TTL, ADMIN_STATS_TTL_JITTER);
                    redisCacheService.setJsonWithJitter(
                            ADMIN_STATS_STALE_CACHE_KEY, stats, ADMIN_STATS_STALE_TTL, ADMIN_STATS_TTL_JITTER);
                    return stats;
                }),
                stale
        );
    }

    private AdminStatsResponse buildStats() {
        long userCount = userAccountRepository.count();
        long mealRecordCount = mealRecordRepository.count();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todayNormalUploadCount = mealRecordRepository.countByCreatedAtAfter(todayStart);
        long todayAbnormalUploadCount = nonFoodUploadEventRepository.countByCreatedAtAfter(todayStart);

        long knowledgeChunkCount = knowledgeIndexService.countChunks();

        return new AdminStatsResponse(
                userCount,
                mealRecordCount,
                todayNormalUploadCount,
                todayAbnormalUploadCount,
                knowledgeChunkCount
        );
    }

    public AdminDashboardResponse dashboard() {
        AdminStatsResponse overview = stats();

        AdminSystemResponse system = new AdminSystemResponse(
                "Spring Boot",
                "PostgreSQL",
                "pgvector",
                pgVectorKnowledgeService.embeddingProviderName(),
                pgVectorKnowledgeService.embeddingDimension(),
                aiModel,
                apiKey != null && !apiKey.isBlank(),
                baseUrl != null && !baseUrl.isBlank(),
                "resources/knowledge/diet_guides.md",
                aiStatusService.recentCalls(),
                aiStatusService.metrics()
        );
        List<com.example.mealcheck.dto.NonFoodUploadAlert> alerts = nonFoodUploadGuardService.activeAlerts();
        AdminOperationsResponse operations = new AdminOperationsResponse(
                userAccountRepository.countByLastUploadAtAfter(LocalDateTime.now().minusDays(7)),
                mealRecordRepository.countByCreatedAtAfter(LocalDateTime.now().minusDays(7)),
                aiStatusService.successCount(),
                aiStatusService.failureCount(),
                alerts.size()
        );
        RagEvaluationSummaryResponse ragEvaluation = ragEvaluationService.summary24h();
        AdminUploadTrendResponse uploadTrends = buildUploadTrends();
        AdminAssistantStatsResponse assistantStats = buildAssistantStats();

        return new AdminDashboardResponse(overview, system, alerts, operations, ragEvaluation, uploadTrends, assistantStats);
    }

    private AdminAssistantStatsResponse buildAssistantStats() {
        long conversationCount = assistantConversationRepository.count();
        long questionCount = assistantConversationMessageRepository.countByRole("user");
        double averageTurns = conversationCount == 0
                ? 0.0
                : Math.round((double) questionCount / conversationCount * 100.0) / 100.0;

        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        Map<LocalDate, Integer> questionsByDay = analyticsQueryService.assistantQuestionTrend(start, today);

        List<AdminAssistantStatsResponse.MetricItem> questionsByUser =
                assistantConversationMessageRepository.countUserQuestionsByUsername()
                        .stream()
                        .limit(10)
                        .map(row -> new AdminAssistantStatsResponse.MetricItem(
                                String.valueOf(row[0]),
                                toInt((Long) row[1])
                        ))
                        .toList();

        List<AdminAssistantStatsResponse.MetricItem> questionCategories = topAssistantCategories(
                assistantConversationMessageRepository.findTop500ByRoleOrderByCreatedAtDesc("user")
        );

        return new AdminAssistantStatsResponse(
                conversationCount,
                questionCount,
                averageTurns,
                assistantProactiveAdviceService.triggerCount(),
                questionsByUser,
                assistantTrendItems(start, questionsByDay),
                questionCategories,
                pgVectorKnowledgeService.topHitChunks(8)
        );
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
        return analyticsQueryService.mealAnalytics(username, from, to, minScore, maxScore);
    }

    public AdminKnowledgeChunkPageResponse knowledgeChunks(String keyword, String source, String sort, int page, int size) {
        return pgVectorKnowledgeService.listAdminChunks(keyword, source, sort, page, size);
    }

    public AdminAuditLogPageResponse auditLogs(int page, int size) {
        return adminAuditService.list(page, size);
    }

    @Transactional(readOnly = true)
    public AdminNonFoodUploadEventPageResponse nonFoodUploads(int page, int size, String username, boolean blockedOnly) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (blockedOnly) {
            Page<NonFoodUploadEvent> filtered = nonFoodUploadEventRepository.findActiveBlocked(
                    username == null ? "" : username.trim(), LocalDateTime.now(), pageable);
            return new AdminNonFoodUploadEventPageResponse(
                    filtered.getContent().stream().map(event -> toNonFoodUploadResponse(event, true)).toList(),
                    filtered.getTotalElements(),
                    filtered.getTotalPages(),
                    filtered.getNumber(),
                    filtered.getSize()
            );
        }
        Page<NonFoodUploadEvent> events = nonFoodUploadEventRepository.findAll(
                nonFoodUploadSpecification(username),
                pageable
        );
        Set<String> blockedUsernames = nonFoodUploadGuardService.activeBlockedUsernames(
                events.getContent().stream().map(NonFoodUploadEvent::getUsername).collect(Collectors.toSet()));
        return new AdminNonFoodUploadEventPageResponse(
                events.getContent().stream()
                        .map(event -> toNonFoodUploadResponse(event, blockedUsernames.contains(event.getUsername())))
                        .toList(),
                events.getTotalElements(),
                events.getTotalPages(),
                events.getNumber(),
                events.getSize()
        );
    }

    public Object reindexKnowledge() {
        Object result = knowledgeIndexService.reindex();
        clearAdminCaches();
        return result;
    }

    public RagBenchmarkResponse ragBenchmark() {
        return ragBenchmarkService.evaluate();
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
        mealRecordPersistenceService.delete(record);
        imageStorageService.deleteAfterCommit(imagePath);
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
        userAccountRepository.delete(user);
        userAccountRepository.flush();
        imagePaths.forEach(imageStorageService::deleteAfterCommit);
        clearAdminCaches();
        adminAuditService.log(currentAdmin, "DELETE_USER", "user", userId, "delete user " + user.getUsername());
    }

    private void clearAdminCaches() {
        redisCacheService.delete(ADMIN_STATS_CACHE_KEY);
        redisCacheService.delete(ADMIN_STATS_STALE_CACHE_KEY);
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

    private AdminNonFoodUploadEventResponse toNonFoodUploadResponse(NonFoodUploadEvent event, boolean currentlyBlocked) {
        int observedMinutes = Math.max(1, event.getObservedMinutes());
        double perMinuteRate = Math.round((double) event.getWindowCount() / observedMinutes * 100.0) / 100.0;
        return new AdminNonFoodUploadEventResponse(
                event.getId(),
                event.getUsername(),
                event.getDisplayName(),
                event.getReason(),
                event.getWindowCount(),
                event.getWindowMinutes(),
                observedMinutes,
                perMinuteRate,
                event.isThresholdReached(),
                currentlyBlocked,
                event.getCreatedAt()
        );
    }

    private List<AdminAssistantStatsResponse.MetricItem> assistantTrendItems(LocalDate start, Map<LocalDate, Integer> values) {
        return IntStream.rangeClosed(0, 6)
                .mapToObj(start::plusDays)
                .map(date -> new AdminAssistantStatsResponse.MetricItem(date.toString(), values.getOrDefault(date, 0)))
                .toList();
    }

    private List<AdminAssistantStatsResponse.MetricItem> topAssistantCategories(List<AssistantConversationMessage> messages) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        messages.stream()
                .map(AssistantConversationMessage::getText)
                .filter(text -> text != null && !text.isBlank())
                .map(this::classifyAssistantQuestion)
                .forEach(category -> counts.merge(category, 1, Integer::sum));

        return counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(8)
                .map(entry -> new AdminAssistantStatsResponse.MetricItem(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String classifyAssistantQuestion(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        if (containsAny(text, "减脂", "减肥", "瘦", "控卡", "低脂", "fat")) {
            return "减脂控卡";
        }
        if (containsAny(text, "增肌", "蛋白", "肌肉", "训练", "muscle")) {
            return "增肌蛋白";
        }
        if (containsAny(text, "食堂", "怎么选", "搭配", "均衡", "下一餐")) {
            return "食堂搭配";
        }
        if (containsAny(text, "炸", "烤肉", "烧烤", "奶茶", "甜", "饮料", "高油", "高糖")) {
            return "高油高糖";
        }
        if (containsAny(text, "蔬菜", "水果", "纤维", "维生素")) {
            return "蔬果摄入";
        }
        if (containsAny(text, "这周", "最近", "复盘", "趋势", "怎么样")) {
            return "饮食复盘";
        }
        return "日常咨询";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int toInt(Long value) {
        if (value == null) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
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

    private Specification<NonFoodUploadEvent> nonFoodUploadSpecification(String username) {
        return (root, query, cb) -> {
            if (username == null || username.isBlank()) {
                return cb.conjunction();
            }
            return cb.like(cb.lower(root.get("username")), "%" + username.trim().toLowerCase() + "%");
        };
    }

    private AdminUploadTrendResponse buildUploadTrends() {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        return analyticsQueryService.uploadTrends(start, today);
    }

    private List<AdminUploadTrendResponse.MetricItem> trendItems(LocalDate start, Map<LocalDate, Integer> values) {
        return IntStream.rangeClosed(0, 6)
                .mapToObj(start::plusDays)
                .map(date -> new AdminUploadTrendResponse.MetricItem(date.toString(), values.getOrDefault(date, 0)))
                .toList();
    }

}
