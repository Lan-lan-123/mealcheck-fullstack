package com.example.mealcheck.repository;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.WeeklyReportSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WeeklyReportSnapshotRepository extends JpaRepository<WeeklyReportSnapshot, Long> {
    Optional<WeeklyReportSnapshot> findFirstByUserOrderByGeneratedAtDesc(UserAccount user);
    @Query("select s from WeeklyReportSnapshot s where s.user=:user and s.days=:days and s.stale=false order by s.generatedAt desc,s.id desc limit 1")
    Optional<WeeklyReportSnapshot> findFirstByUserAndDaysOrderByGeneratedAtDesc(UserAccount user, int days);
    Optional<WeeklyReportSnapshot> findByJobId(Long jobId);
}
