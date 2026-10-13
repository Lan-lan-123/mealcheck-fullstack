package com.example.mealcheck.service;

import com.example.mealcheck.dto.*;
import com.example.mealcheck.dto.JournalDtos.*;
import com.example.mealcheck.entity.*;
import com.example.mealcheck.repository.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;

@Service
public class MealJournalService {
    private final UserAccountRepository users;
    private final MealRecordRepository meals;
    private final MealScoringService scoring;
    private final AdviceGenerationService advice;
    private final UserGoalService goals;
    private final UserDietProfileService profiles;
    private final MealReportInvalidationService invalidation;
    private final MealAnalysisService analysis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public MealJournalService(UserAccountRepository users, MealRecordRepository meals, MealScoringService scoring,
        AdviceGenerationService advice, UserGoalService goals, UserDietProfileService profiles,
        MealReportInvalidationService invalidation, MealAnalysisService analysis, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.users=users; this.meals=meals; this.scoring=scoring; this.advice=advice; this.goals=goals;
        this.profiles=profiles; this.invalidation=invalidation; this.analysis=analysis; this.jdbc=jdbc; this.mapper=mapper;
    }
    private UserAccount lock(UserPrincipal principal) {
        return users.findByIdForUpdate(principal.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    public static LocalDateTime validateTime(String value) {
        LocalDateTime time=AppTime.parseFilterTime(value);
        if(time==null || time.isBefore(AppTime.startOfDay(LocalDate.of(2000,1,1))) || (time.isAfter(AppTime.now().plusMinutes(15)) || AppTime.businessDate(time).isAfter(AppTime.today())))
            throw new IllegalArgumentException("用餐时间须在 2000 年之后，且不能晚于当前时间 15 分钟。");
        return time;
    }
    @Transactional
    public MealRecordResponse create(UserPrincipal principal, ManualMeal request) {
        var user=lock(principal);
        var time=validateTime(request.occurredAt());
        var recognized=new RecognitionResult();
        recognized.setFoods(request.foods().stream().map(f -> new FoodItem(f.name().trim(),f.category(),1.0,"用户手动记录")).toList());
        recognized.setFoods(scoring.acceptedFoods(recognized));
        if(recognized.getFoods().isEmpty()) throw new IllegalArgumentException("请至少填写一项有效食物。");
        var goal=goals.effectiveGoal(user,request.goal());
        var evaluation=scoring.evaluate(recognized,goal);
        var record=new MealRecord(); record.setUser(user); record.setGoal(goal); record.setOccurredAt(time);
        record.setMealType(request.mealType()); record.setRecordSource("MANUAL"); record.setTimeConfirmed(true); record.setNote(request.note());
        record.setScore(evaluation.getScore()); record.setSummary(evaluation.getSummary());
        record.setDetectedFoodsJson(Jsons.toJson(mapper,recognized.getFoods()));
        record.setOriginalFoodsJson(record.getDetectedFoodsJson()); record.setScoringVersion(MealScoringService.VERSION);
        record.setCategoryCountsJson(Jsons.toJson(mapper,evaluation.getCategoryCounts()));
        record.setRiskTagsJson(Jsons.toJson(mapper,evaluation.getRiskTags()));
        record.setAdvice(advice.correctedAdvice(recognized,evaluation,goal));
        meals.saveAndFlush(record); clearSkip(record); profiles.applyMeal(user,record); invalidation.mealDates(user.getId(),AppTime.businessDate(record.getOccurredAt()));
        return analysis.toResponse(record);
    }
    @Transactional
    public MealRecordResponse update(UserPrincipal principal,long id,Metadata request) {
        lock(principal);
        var record=meals.findById(id).filter(m->m.getUser().getId().equals(principal.getId()))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"餐食不存在"));
        if(record.getMetadataVersion()!=request.expectedVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT,"用餐信息已被修改，请刷新后再试。");
        var previousDay=AppTime.businessDate(record.getOccurredAt());
        var newTime=validateTime(request.occurredAt());
        record.setOccurredAt(newTime); record.setMealType(request.mealType());
        record.setNote(request.note()); record.setTimeConfirmed(true); record.setMetadataVersion(record.getMetadataVersion()+1);
        meals.saveAndFlush(record); clearSkip(record); if(!previousDay.equals(AppTime.businessDate(record.getOccurredAt()))) invalidation.mealDates(principal.getId(),previousDay,AppTime.businessDate(record.getOccurredAt()));
        // Food revision is independent: changing meal time does not invalidate nutrition inputs.
        return analysis.toResponse(record);
    }
    private void clearSkip(MealRecord record) {
        jdbc.update("DELETE FROM meal_slot_declarations WHERE user_id=? AND business_day=? AND meal_type=?",
            record.getUser().getId(),AppTime.businessDate(record.getOccurredAt()),record.getMealType());
    }
    @Transactional
    public void skipped(UserPrincipal principal,SkipSlot request,boolean remove) {
        lock(principal);
        if(request.day().isBefore(LocalDate.of(2000,1,1)))throw new IllegalArgumentException("餐次日期须在 2000 年之后。");
        if(request.day().isAfter(AppTime.today())) throw new IllegalArgumentException("不能提前确认未来餐次未吃。");
        if(!remove) {
            Long count=jdbc.queryForObject("SELECT count(*) FROM meal_records WHERE user_id=? AND meal_type=? AND coalesce(occurred_at,created_at)>=? AND coalesce(occurred_at,created_at)<?",Long.class,
                principal.getId(),request.mealType(),AppTime.startOfDay(request.day()),AppTime.startOfDay(request.day().plusDays(1)));
            if(count!=null && count>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"该餐次已有餐食，请先核对记录。");
            jdbc.update("INSERT INTO meal_slot_declarations(user_id,business_day,meal_type,note) VALUES(?,?,?,?) ON CONFLICT(user_id,business_day,meal_type) DO UPDATE SET note=EXCLUDED.note,updated_at=CURRENT_TIMESTAMP",
                principal.getId(),request.day(),request.mealType(),request.note());
        } else jdbc.update("DELETE FROM meal_slot_declarations WHERE user_id=? AND business_day=? AND meal_type=?",principal.getId(),request.day(),request.mealType());
        // Slot declarations change only tracking data, which reports attach when read.
    }
}
