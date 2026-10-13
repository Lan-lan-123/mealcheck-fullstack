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
    interface TrendFacts { String getFoods(); String getRisks(); Long getOccurrences(); }

    @Query(value = """
            WITH selected AS (
              SELECT id,score,coalesce(occurred_at,created_at) AS created_at FROM meal_records WHERE user_id=:userId AND coalesce(occurred_at,created_at)>=:since
            )
            SELECT count(*) AS total,avg(score) AS average,count(score) AS scored,
              count(*) FILTER(WHERE score<70) AS low,count(*) FILTER(WHERE score>=80) AS high,
              (SELECT score FROM selected WHERE score IS NOT NULL ORDER BY created_at,id LIMIT 1) AS "firstScore",
              (SELECT score FROM selected WHERE score IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1) AS "lastScore"
            FROM selected
            """, nativeQuery = true)
    TrendSummary trendSummary(long userId, LocalDateTime since);

    @Query(value = """
            SELECT CAST(coalesce(occurred_at,created_at) AT TIME ZONE :storageZone AT TIME ZONE :zone AS date) AS day,avg(score) AS average FROM meal_records
            WHERE user_id=:userId AND coalesce(occurred_at,created_at)>=:since AND score IS NOT NULL
            GROUP BY 1 ORDER BY day
            """, nativeQuery = true)
    List<DailyScore> trendDailyScores(long userId, LocalDateTime since, String zone, String storageZone);

    default List<DailyScore> trendDailyScores(long userId, LocalDateTime since, String zone) {
        return trendDailyScores(userId, since, zone, AppTime.storageZone().getId());
    }

    @Query(value = """
            WITH selected AS (
              SELECT detected_foods_json,risk_tags_json,
                row_number() OVER (ORDER BY coalesce(occurred_at,created_at),id) AS position
              FROM meal_records WHERE user_id=:userId AND coalesce(occurred_at,created_at)>=:since
            )
            SELECT detected_foods_json AS foods,risk_tags_json AS risks,count(*) AS occurrences
            FROM selected GROUP BY detected_foods_json,risk_tags_json ORDER BY min(position)
            """, nativeQuery = true)
    List<TrendFacts> trendFacts(long userId, LocalDateTime since);

    @Query("select m from MealRecord m where m.user=:user order by coalesce(m.occurredAt,m.createdAt) desc,m.id desc limit 30")
    List<MealRecord> findTop30ByUserOrderByCreatedAtDesc(UserAccount user);

    Page<MealRecord> findByUser(UserAccount user, Pageable pageable);

    List<MealRecord> findByUser(UserAccount user);

    @Query("select m from MealRecord m where m.user=:user and coalesce(m.occurredAt,m.createdAt)>=:since order by coalesce(m.occurredAt,m.createdAt),m.id")
    List<MealRecord> findByUserAndCreatedAtAfterOrderByCreatedAtAsc(UserAccount user, LocalDateTime since);
    @Query("select m from MealRecord m where m.user=:user and coalesce(m.occurredAt,m.createdAt)>=:start and coalesce(m.occurredAt,m.createdAt)<:end order by coalesce(m.occurredAt,m.createdAt),m.id")
    List<MealRecord> findByUserAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            UserAccount user, LocalDateTime start, LocalDateTime end);
    List<MealRecord> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime since);

    long countByCreatedAtAfter(LocalDateTime time);

    @Query("select record.storedImagePath from MealRecord record where record.storedImagePath is not null")
    List<String> findAllStoredImagePaths();

    @EntityGraph(attributePaths = "user")
    List<MealRecord> findTop100ByOrderByCreatedAtDesc();
}
