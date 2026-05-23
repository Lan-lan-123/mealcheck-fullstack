package com.example.mealcheck.repository;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.WeeklyReportSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WeeklyReportSnapshotRepository extends JpaRepository<WeeklyReportSnapshot, Long> {
    Optional<WeeklyReportSnapshot> findFirstByUserOrderByGeneratedAtDesc(UserAccount user);
}
