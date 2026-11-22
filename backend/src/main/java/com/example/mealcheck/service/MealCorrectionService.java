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
        var previousContribution = profiles.contribution(record);
        int previousScore = record.getScore();
        String previousVersion = record.getScoringVersion();
        var priorFoods = Jsons.fromJson(mapper, record.getDetectedFoodsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<FoodItem>>() {});
        RecognitionResult recognition = new RecognitionResult();
        recognition.setFoods(request.foods().stream().map(food -> {
            FoodItem corrected = food.toFoodItem("用户确认");
            // Base-category edits must not silently erase independent risks for the same named food.
            if (priorFoods != null) priorFoods.stream()
                    .filter(prior -> prior != null && corrected.getName().equals(prior.getName()))
                    .findFirst().ifPresent(prior -> {
                        if (food.attributes() == null) corrected.setAttributes(FoodClassification.normalized(prior).getAttributes());
                        corrected.setReviewIssues(prior.getReviewIssues().stream().filter(issue -> switch (issue.code()) {
                            case "FRYING_CONFLICT" -> corrected.getAttributes() == null || corrected.getAttributes().getFried() == null;
                            case "OIL_CONFLICT" -> corrected.getAttributes() == null || corrected.getAttributes().getHighOil() == null;
                            case "CATEGORY_CONFLICT" -> "other".equals(corrected.getCategory());
                            default -> false;
                        }).toList());
                    });
            return corrected;
        }).toList());
        recognition.setFoods(scoring.acceptedFoods(recognition));
        var evaluation = scoring.evaluate(recognition, record.getGoal());
        String before = record.getDetectedFoodsJson();
        String after = Jsons.toJson(mapper, recognition.getFoods());
        if (before == null) before = "[]";
        if (record.getOriginalFoodsJson() == null) record.setOriginalFoodsJson(before);
        // Account lock serializes uploads, corrections and deletes; the immutable
        // snapshot retains the old contribution after this managed entity changes.
        record.setDetectedFoodsJson(after);
        record.setRevision(record.getRevision() + 1);
        record.setScoringVersion(MealScoringService.VERSION);
        record.setScore(evaluation.getScore());
        record.setScoreBreakdownJson(Jsons.toJson(mapper, evaluation.getScoreBreakdown()));
        record.setSummary(evaluation.getSummary());
        record.setCategoryCountsJson(Jsons.toJson(mapper, evaluation.getCategoryCounts()));
        record.setRiskTagsJson(Jsons.toJson(mapper, evaluation.getRiskTags()));
        // A correction must not hold database locks while waiting for an AI API.
        record.setAdvice(advice.correctedAdvice(recognition, evaluation, record.getGoal(), user.getId()));
        record.setAdviceStatus("USER_CORRECTION");record.setAdviceFailureCode(null);
        meals.saveAndFlush(record);
        profiles.replaceMeal(user, previousContribution, record);
        invalidation.mealDates(user.getId(),com.example.mealcheck.util.AppTime.businessDate(record.getOccurredAt()));
        jdbc.update("INSERT INTO meal_corrections(user_id,record_id,revision,before_foods_json,after_foods_json,reason,scoring_version) VALUES (?,?,?,?,?,?,?)",
                user.getId(), id, record.getRevision(), before, after, request.reason().trim(), MealScoringService.VERSION);
        var response = new MealAnalysisResponse();
        response.setRecordId(id); response.setCreatedAt(record.getCreatedAt());
        response.setRecognition(recognition); response.setEvaluation(evaluation); response.setAdvice(record.getAdvice());
        response.setAdviceStatus(record.getAdviceStatus());response.setAdviceFailureCode(record.getAdviceFailureCode());
        response.setRevision(record.getRevision()); response.setScoringVersion(record.getScoringVersion());
        response.setScoreChange(new MealAnalysisResponse.ScoreChange(previousScore, record.getScore(), previousVersion, record.getScoringVersion()));
        jdbc.update("UPDATE meal_analysis_requests SET response_json=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND record_id=? AND status='SUCCEEDED'", Jsons.toJson(mapper, response), user.getId(), id);
        return response;
    }
}
