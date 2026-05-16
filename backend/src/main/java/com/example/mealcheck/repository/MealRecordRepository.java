package com.example.mealcheck.repository;

import com.example.mealcheck.entity.MealRecord;
import com.example.mealcheck.entity.UserAccount;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface MealRecordRepository extends JpaRepository<MealRecord, Long>, JpaSpecificationExecutor<MealRecord> {

    List<MealRecord> findTop30ByUserOrderByCreatedAtDesc(UserAccount user);

    Page<MealRecord> findByUser(UserAccount user, Pageable pageable);

    List<MealRecord> findByUser(UserAccount user);

    List<MealRecord> findByUserAndCreatedAtAfterOrderByCreatedAtAsc(UserAccount user, LocalDateTime since);

    long countByCreatedAtAfter(LocalDateTime time);

    @EntityGraph(attributePaths = "user")
    List<MealRecord> findTop100ByOrderByCreatedAtDesc();
}
