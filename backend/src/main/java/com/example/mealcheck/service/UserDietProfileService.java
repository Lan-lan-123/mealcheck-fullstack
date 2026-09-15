package com.example.mealcheck.service;

import com.example.mealcheck.dto.FoodItem;
import com.example.mealcheck.dto.UserDietProfileResponse;
import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserDietProfile;
import com.example.mealcheck.repository.MealRecordRepository;
import com.example.mealcheck.repository.UserDietProfileRepository;
import com.example.mealcheck.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UserDietProfileService {
    private final UserDietProfileRepository profileRepository;
    private final MealRecordRepository mealRecordRepository;
    private final ObjectMapper objectMapper;

    public UserDietProfileService(UserDietProfileRepository profileRepository,
                                  MealRecordRepository mealRecordRepository,
                                  ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.mealRecordRepository = mealRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UserDietProfile refresh(UserAccount user) {
        List<MealRecord> records = mealRecordRepository.findByUser(user);
        UserDietProfile profile = profileRepository.findByUser(user).orElseGet(() -> {
            UserDietProfile created = new UserDietProfile();
            created.setUser(user);
            return created;
        });

        long totalScore = records.stream().map(MealRecord::getScore).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        Map<String, Long> foodCounts = countValues(
                records.stream().flatMap(record -> extractFoodNames(record).stream()).toList());
        Map<String, Long> riskCounts = countValues(
                records.stream().flatMap(record -> extractRiskTags(record).stream()).toList());
        Map<String, Long> goalCounts = countValues(records.stream().map(MealRecord::getGoal).toList());
        List<String> foods = topValues(foodCounts, 8);
        List<String> risks = topValues(riskCounts, 8);
        String preferredGoal = topValues(goalCounts, 1).stream().findFirst().orElse("");
        int averageScore = averageScore(totalScore, records.size());

        profile.setTotalScore(totalScore);
        profile.setTotalMeals(records.size());
        profile.setAverageScore(averageScore);
        profile.setAggregateInitialized(true);
        profile.setFoodCountsJson(Jsons.toJson(objectMapper, foodCounts));
        profile.setRiskCountsJson(Jsons.toJson(objectMapper, riskCounts));
        profile.setGoalCountsJson(Jsons.toJson(objectMapper, goalCounts));
        profile.setPreferredGoal(preferredGoal);
        profile.setCommonFoodsJson(Jsons.toJson(objectMapper, foods));
        profile.setCommonRisksJson(Jsons.toJson(objectMapper, risks));
        profile.setProfileSummary(buildSummary(records.size(), averageScore, preferredGoal, foods, risks));
        profile.setUpdatedAt(LocalDateTime.now());
        return profileRepository.save(profile);
    }

    @Transactional
    public UserDietProfile applyMeal(UserAccount user, MealRecord record) {
        UserDietProfile profile = profileRepository.findByUser(user).orElse(null);
        if (profile != null && !profile.isAggregateInitialized()) {
            return refresh(user);
        }
        if (profile == null) {
            profile = new UserDietProfile();
            profile.setUser(user);
            profile.setAggregateInitialized(true);
        }

        long totalMeals = profile.getTotalMeals() + 1L;
        long totalScore = profile.getTotalScore() + safeScore(record);
        Map<String, Long> foodCounts = readCountMap(profile.getFoodCountsJson());
        Map<String, Long> riskCounts = readCountMap(profile.getRiskCountsJson());
        Map<String, Long> goalCounts = readCountMap(profile.getGoalCountsJson());
        updateCounts(foodCounts, extractFoodNames(record), 1L);
        updateCounts(riskCounts, extractRiskTags(record), 1L);
        updateCounts(goalCounts, List.of(nullToEmpty(record.getGoal())), 1L);
        return updateDerivedProfile(profile, totalMeals, totalScore, foodCounts, riskCounts, goalCounts);
    }

    @Transactional
    public void removeMeal(UserAccount user, MealRecord record) {
        profileRepository.findByUser(user).ifPresent(profile -> {
            if (!profile.isAggregateInitialized()) {
                profileRepository.delete(profile);
                return;
            }
            long totalMeals = Math.max(0L, profile.getTotalMeals() - 1L);
            long totalScore = Math.max(0L, profile.getTotalScore() - safeScore(record));
            Map<String, Long> foodCounts = readCountMap(profile.getFoodCountsJson());
            Map<String, Long> riskCounts = readCountMap(profile.getRiskCountsJson());
            Map<String, Long> goalCounts = readCountMap(profile.getGoalCountsJson());
            updateCounts(foodCounts, extractFoodNames(record), -1L);
            updateCounts(riskCounts, extractRiskTags(record), -1L);
            updateCounts(goalCounts, List.of(nullToEmpty(record.getGoal())), -1L);
            updateDerivedProfile(profile, totalMeals, totalScore, foodCounts, riskCounts, goalCounts);
        });
    }

    @Transactional
    public UserDietProfile getOrRefresh(UserAccount user) {
        return profileRepository.findByUser(user)
                .filter(UserDietProfile::isAggregateInitialized)
                .orElseGet(() -> refresh(user));
    }

    public UserDietProfileResponse toResponse(UserDietProfile profile) {
        return new UserDietProfileResponse(
                profile.getTotalMeals(),
                profile.getAverageScore(),
                profile.getPreferredGoal(),
                readStringList(profile.getCommonFoodsJson()),
                readStringList(profile.getCommonRisksJson()),
                profile.getProfileSummary(),
                profile.getUpdatedAt()
        );
    }

    public String promptText(UserDietProfile profile) {
        if (profile == null || profile.getTotalMeals() == 0) {
            return "No long-term diet profile yet.";
        }
        return profile.getProfileSummary()
                + " Common foods: " + String.join(", ", readStringList(profile.getCommonFoodsJson()))
                + ". Common risks: " + String.join(", ", readStringList(profile.getCommonRisksJson()))
                + ". Preferred goal: " + nullToEmpty(profile.getPreferredGoal()) + ".";
    }

    public List<String> commonFoods(UserDietProfile profile) {
        return profile == null ? List.of() : readStringList(profile.getCommonFoodsJson());
    }

    public List<String> commonRisks(UserDietProfile profile) {
        return profile == null ? List.of() : readStringList(profile.getCommonRisksJson());
    }

    private String buildSummary(long total, int averageScore, String goal, List<String> foods, List<String> risks) {
        if (total == 0) {
            return "还没有长期饮食画像，建议先持续上传饮食记录。";
        }
        String riskText = risks.isEmpty() ? "风险标签暂不明显" : "常见风险包括 " + String.join("、", risks.subList(0, Math.min(3, risks.size())));
        String foodText = foods.isEmpty() ? "常见食物暂不明显" : "常见食物包括 " + String.join("、", foods.subList(0, Math.min(4, foods.size())));
        return "累计 " + total + " 条饮食记录，长期平均评分约 " + averageScore + "。" + foodText + "，" + riskText
                + "。常用目标为 " + nullToEmpty(goal) + "。";
    }

    private List<String> extractFoodNames(MealRecord record) {
        List<FoodItem> foods = Jsons.fromJson(objectMapper, record.getDetectedFoodsJson(), new TypeReference<List<FoodItem>>() {});
        if (foods == null) return List.of();
        return foods.stream().map(FoodItem::getName).filter(name -> name != null && !name.isBlank()).toList();
    }

    private List<String> extractRiskTags(MealRecord record) {
        List<String> tags = Jsons.fromJson(objectMapper, record.getRiskTagsJson(), new TypeReference<List<String>>() {});
        return tags == null ? List.of() : tags.stream().filter(tag -> tag != null && !tag.isBlank()).toList();
    }

    private Map<String, Long> countValues(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(String::trim, LinkedHashMap::new, Collectors.counting()));
    }

    private List<String> topValues(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private UserDietProfile updateDerivedProfile(UserDietProfile profile,
                                                 long totalMeals,
                                                 long totalScore,
                                                 Map<String, Long> foodCounts,
                                                 Map<String, Long> riskCounts,
                                                 Map<String, Long> goalCounts) {
        List<String> foods = topValues(foodCounts, 8);
        List<String> risks = topValues(riskCounts, 8);
        String preferredGoal = topValues(goalCounts, 1).stream().findFirst().orElse("");
        int averageScore = averageScore(totalScore, totalMeals);
        profile.setTotalMeals(totalMeals);
        profile.setTotalScore(totalScore);
        profile.setAverageScore(averageScore);
        profile.setPreferredGoal(preferredGoal);
        profile.setFoodCountsJson(Jsons.toJson(objectMapper, foodCounts));
        profile.setRiskCountsJson(Jsons.toJson(objectMapper, riskCounts));
        profile.setGoalCountsJson(Jsons.toJson(objectMapper, goalCounts));
        profile.setCommonFoodsJson(Jsons.toJson(objectMapper, foods));
        profile.setCommonRisksJson(Jsons.toJson(objectMapper, risks));
        profile.setProfileSummary(buildSummary(totalMeals, averageScore, preferredGoal, foods, risks));
        profile.setAggregateInitialized(true);
        profile.setUpdatedAt(LocalDateTime.now());
        return profileRepository.save(profile);
    }

    private Map<String, Long> readCountMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Long> values = Jsons.fromJson(
                objectMapper, json, new TypeReference<LinkedHashMap<String, Long>>() {});
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private void updateCounts(Map<String, Long> counts, List<String> values, long delta) {
        for (String raw : values) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim();
            long next = counts.getOrDefault(value, 0L) + delta;
            if (next <= 0L) {
                counts.remove(value);
            } else {
                counts.put(value, next);
            }
        }
    }

    private long safeScore(MealRecord record) {
        return record.getScore() == null ? 0L : Math.max(0, record.getScore());
    }

    private int averageScore(long totalScore, long totalMeals) {
        return totalMeals <= 0L ? 0 : (int) Math.round((double) totalScore / totalMeals);
    }

    private List<String> readStringList(String json) {
        List<String> values = Jsons.fromJson(objectMapper, json, new TypeReference<List<String>>() {});
        return values == null ? List.of() : values;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
