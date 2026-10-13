package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class MealCorrectionService {
    private final UserAccountRepository users;
    private final MealRecordRepository meals;
    private final UserDietProfileService profiles;
    private final MealScoringService scoring;
    private final AdviceGenerationService advice;
    private final MealReportInvalidationService invalidation;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public MealCorrectionService(UserAccountRepository users, MealRecordRepository meals, UserDietProfileService profiles,
            MealScoringService scoring, AdviceGenerationService advice, MealReportInvalidationService invalidation,
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.users=users; this.meals=meals; this.profiles=profiles; this.scoring=scoring;
        this.advice=advice; this.invalidation=invalidation; this.jdbc=jdbc; this.mapper=mapper;
    }
    @Transactional
    public MealAnalysisResponse correct(UserPrincipal principal, Long id, MealCorrectionRequest request) {
        var user = users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        var record = meals.findById(id).filter(item -> item.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "饮食记录不存在"));
        if (record.getRevision() != request.expectedRevision()) throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被修改，请刷新后重试");
        RecognitionResult recognition = new RecognitionResult();
        recognition.setFoods(request.foods().stream().map(food -> new FoodItem(food.name().trim(), food.category(), 1.0, "用户确认")).toList());
        recognition.setFoods(scoring.acceptedFoods(recognition));
        var evaluation = scoring.evaluate(recognition, record.getGoal());
        String before = record.getDetectedFoodsJson();
        String after = Jsons.toJson(mapper, recognition.getFoods());
        if (before == null) before = "[]";
        if (record.getOriginalFoodsJson() == null) record.setOriginalFoodsJson(before);
        // Account lock serializes uploads, corrections and deletes. Rebuild the
        // aggregate after this infrequent edit, including legacy profiles.
        record.setDetectedFoodsJson(after);
        record.setRevision(record.getRevision() + 1);
        record.setScoringVersion(MealScoringService.VERSION);
        record.setScore(evaluation.getScore());
        record.setSummary(evaluation.getSummary());
        record.setCategoryCountsJson(Jsons.toJson(mapper, evaluation.getCategoryCounts()));
        record.setRiskTagsJson(Jsons.toJson(mapper, evaluation.getRiskTags()));
        // A correction must not hold database locks while waiting for an AI API.
        record.setAdvice(advice.correctedAdvice(recognition, evaluation, record.getGoal()));
        meals.saveAndFlush(record);
        profiles.refresh(user);
        invalidation.mealDates(user.getId(),com.example.mealcheck.util.AppTime.businessDate(record.getOccurredAt()));
        jdbc.update("INSERT INTO meal_corrections(user_id,record_id,revision,before_foods_json,after_foods_json,reason,scoring_version) VALUES (?,?,?,?,?,?,?)",
                user.getId(), id, record.getRevision(), before, after, request.reason().trim(), MealScoringService.VERSION);
        var response = new MealAnalysisResponse();
        response.setRecordId(id); response.setCreatedAt(record.getCreatedAt());
        response.setRecognition(recognition); response.setEvaluation(evaluation); response.setAdvice(record.getAdvice());
        response.setRevision(record.getRevision()); response.setScoringVersion(record.getScoringVersion());
        jdbc.update("UPDATE meal_analysis_requests SET response_json=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND record_id=? AND status='SUCCEEDED'", Jsons.toJson(mapper, response), user.getId(), id);
        return response;
    }
}
