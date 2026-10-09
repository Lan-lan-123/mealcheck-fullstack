package com.example.mealcheck.repository;

import com.example.mealcheck.util.AppTime;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface MealRecordRepository extends JpaRepository<MealRecord, Long>, JpaSpecificationExecutor<MealRecord> {

    interface TrendSummary {
        Long getTotal(); Double getAverage(); Long getLow(); Long getHigh(); Long getScored();
        Integer getFirstScore(); Integer getLastScore();
    }
    interface DailyScore { java.time.LocalDate getDay(); Double getAverage(); }
    interface TrendFacts { String getFoods(); String getRisks(); }

    @Query(value = """
            WITH selected AS (
              SELECT id,score,created_at FROM meal_records WHERE user_id=:userId AND created_at>:since
            )
            SELECT count(*) AS total,avg(score) AS average,count(score) AS scored,
              count(*) FILTER(WHERE score<70) AS low,count(*) FILTER(WHERE score>=80) AS high,
              (SELECT score FROM selected WHERE score IS NOT NULL ORDER BY created_at,id LIMIT 1) AS "firstScore",
              (SELECT score FROM selected WHERE score IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1) AS "lastScore"
            FROM selected
            """, nativeQuery = true)
    TrendSummary trendSummary(long userId, LocalDateTime since);

    @Query(value = """
            SELECT CAST(created_at AT TIME ZONE :storageZone AT TIME ZONE :zone AS date) AS day,avg(score) AS average FROM meal_records
            WHERE user_id=:userId AND created_at>:since AND score IS NOT NULL
            GROUP BY 1 ORDER BY day
            """, nativeQuery = true)
    List<DailyScore> trendDailyScores(long userId, LocalDateTime since, String zone, String storageZone);

    default List<DailyScore> trendDailyScores(long userId, LocalDateTime since, String zone) {
        return trendDailyScores(userId, since, zone, AppTime.storageZone().getId());
    }

    @Query(value = """
            SELECT detected_foods_json AS foods,risk_tags_json AS risks FROM meal_records
            WHERE user_id=:userId AND created_at>:since ORDER BY created_at,id
            """, nativeQuery = true)
    List<TrendFacts> trendFacts(long userId, LocalDateTime since);

    List<MealRecord> findTop30ByUserOrderByCreatedAtDesc(UserAccount user);

    Page<MealRecord> findByUser(UserAccount user, Pageable pageable);

    List<MealRecord> findByUser(UserAccount user);

    List<MealRecord> findByUserAndCreatedAtAfterOrderByCreatedAtAsc(UserAccount user, LocalDateTime since);
    List<MealRecord> findByUserAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            UserAccount user, LocalDateTime start, LocalDateTime end);
    List<MealRecord> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime since);

    long countByCreatedAtAfter(LocalDateTime time);

    @Query("select record.storedImagePath from MealRecord record where record.storedImagePath is not null")
    List<String> findAllStoredImagePaths();

    @EntityGraph(attributePaths = "user")
    List<MealRecord> findTop100ByOrderByCreatedAtDesc();
}
