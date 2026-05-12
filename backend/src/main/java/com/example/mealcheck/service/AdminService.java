package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.AdminDashboardResponse;
import com.example.mealcheck.dto.AdminKnowledgeChunkRequest;
import com.example.mealcheck.dto.AdminKnowledgeChunkResponse;
import com.example.mealcheck.dto.AdminMealPageResponse;
import com.example.mealcheck.dto.AdminMealRecordResponse;
import com.example.mealcheck.dto.AdminStatsResponse;
import com.example.mealcheck.dto.AdminSystemResponse;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class AdminService {

    private final UserAccountRepository userAccountRepository;
    private final MealRecordRepository mealRecordRepository;
    private final KnowledgeIndexService knowledgeIndexService;
    private final PgVectorKnowledgeService pgVectorKnowledgeService;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

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
                        ObjectMapper objectMapper,
                        AppProperties properties) {
        this.userAccountRepository = userAccountRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.knowledgeIndexService = knowledgeIndexService;
        this.pgVectorKnowledgeService = pgVectorKnowledgeService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AdminStatsResponse stats() {
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
                "resources/knowledge/diet_guides.md"
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

    public List<AdminKnowledgeChunkResponse> knowledgeChunks(String title) {
        return pgVectorKnowledgeService.listAdminChunks(title);
    }

    public Object reindexKnowledge() {
        return knowledgeIndexService.reindex();
    }

    public void addKnowledgeChunk(AdminKnowledgeChunkRequest request) {
        pgVectorKnowledgeService.add(
                "admin",
                request.getTitle().trim(),
                request.getContent().trim()
        );
    }

    public void deleteKnowledgeChunk(Long id) {
        boolean deleted = pgVectorKnowledgeService.delete(id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG 知识片段不存在");
        }
    }

    public void updateKnowledgeChunk(Long id, AdminKnowledgeChunkRequest request) {
        boolean updated = pgVectorKnowledgeService.update(
                id,
                request.getTitle().trim(),
                request.getContent().trim()
        );
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG 知识片段不存在");
        }
    }

    @Transactional
    public void deleteMealRecord(Long id) {
        MealRecord record = mealRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));
        String imagePath = record.getStoredImagePath();
        mealRecordRepository.delete(record);
        deleteImageFileSafely(imagePath);
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
        imagePaths.forEach(this::deleteImageFileSafely);
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

    private void deleteImageFileSafely(String storedImagePath) {
        try {
            Path uploadRoot = Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
            Path path = Path.of(storedImagePath).toAbsolutePath().normalize();
            if (!path.startsWith(uploadRoot)) {
                System.err.println("跳过非 uploads 目录图片: " + path);
                return;
            }

            if (Files.exists(path) && Files.isRegularFile(path)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception e) {
            System.err.println("删除用户图片失败: " + storedImagePath + ", " + e.getMessage());
        }
    }
}
