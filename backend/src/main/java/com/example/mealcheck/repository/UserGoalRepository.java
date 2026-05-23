package com.example.mealcheck.repository;

import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserGoalRepository extends JpaRepository<UserGoal, Long> {
    Optional<UserGoal> findFirstByUserAndActiveTrueOrderByCreatedAtDesc(UserAccount user);
    List<UserGoal> findByUserOrderByCreatedAtDesc(UserAccount user);
}
