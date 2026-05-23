package com.example.mealcheck.repository;

import com.example.mealcheck.entity.NonFoodUploadEvent;
import com.example.mealcheck.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface NonFoodUploadEventRepository extends JpaRepository<NonFoodUploadEvent, Long>, JpaSpecificationExecutor<NonFoodUploadEvent> {
    List<NonFoodUploadEvent> findByUser(UserAccount user);
    Optional<NonFoodUploadEvent> findFirstByUsernameAndCreatedAtAfterOrderByCreatedAtAsc(String username, LocalDateTime createdAt);
    long countByCreatedAtAfter(LocalDateTime createdAt);
    List<NonFoodUploadEvent> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime createdAt);
}
