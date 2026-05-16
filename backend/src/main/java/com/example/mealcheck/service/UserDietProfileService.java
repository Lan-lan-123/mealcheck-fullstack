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

        List<Integer> scores = records.stream()
                .map(MealRecord::getScore)
                .filter(Objects::nonNull)
                .toList();
        List<String> foods = topValues(records.stream().flatMap(record -> extractFoodNames(record).stream()).toList(), 8);
        List<String> risks = topValues(records.stream().flatMap(record -> extractRiskTags(record).stream()).toList(), 8);
        String preferredGoal = mostCommon(records.stream().map(MealRecord::getGoal).toList());
        int averageScore = scores.isEmpty()
                ? 0
                : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));

        profile.setTotalMeals(records.size());
        profile.setAverageScore(averageScore);
        profile.setPreferredGoal(preferredGoal);
        profile.setCommonFoodsJson(Jsons.toJson(objectMapper, foods));
        profile.setCommonRisksJson(Jsons.toJson(objectMapper, risks));
        profile.setProfileSummary(buildSummary(records.size(), averageScore, preferredGoal, foods, risks));
        profile.setUpdatedAt(LocalDateTime.now());
        return profileRepository.save(profile);
    }

    @Transactional
    public UserDietProfile getOrRefresh(UserAccount user) {
        return profileRepository.findByUser(user).orElseGet(() -> refresh(user));
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

    private List<String> topValues(List<String> values, int limit) {
        Map<String, Long> counts = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(String::trim, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String mostCommon(List<String> values) {
        return topValues(values, 1).stream().findFirst().orElse("");
    }

    private List<String> readStringList(String json) {
        List<String> values = Jsons.fromJson(objectMapper, json, new TypeReference<List<String>>() {});
        return values == null ? List.of() : values;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
