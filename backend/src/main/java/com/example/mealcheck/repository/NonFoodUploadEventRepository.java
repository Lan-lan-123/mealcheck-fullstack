package com.example.mealcheck.repository;

import com.example.mealcheck.entity.NonFoodUploadEvent;
import com.example.mealcheck.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface NonFoodUploadEventRepository extends JpaRepository<NonFoodUploadEvent, Long>, JpaSpecificationExecutor<NonFoodUploadEvent> {
    List<NonFoodUploadEvent> findByUser(UserAccount user);
    Optional<NonFoodUploadEvent> findFirstByUsernameAndCreatedAtAfterOrderByCreatedAtAsc(String username, LocalDateTime createdAt);
    long countByCreatedAtAfter(LocalDateTime createdAt);
    List<NonFoodUploadEvent> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime createdAt);

    @Query("""
            select event from NonFoodUploadEvent event
            where (:username = '' or lower(event.username) like lower(concat('%', :username, '%')))
              and exists (
                  select restriction.id from UserRestriction restriction
                  where restriction.username = event.username
                    and restriction.restrictionType = 'NON_FOOD_UPLOAD'
                    and restriction.blockedUntil > :now
              )
            """)
    Page<NonFoodUploadEvent> findActiveBlocked(@Param("username") String username,
                                               @Param("now") LocalDateTime now,
                                               Pageable pageable);
}
