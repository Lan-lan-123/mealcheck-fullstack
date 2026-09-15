package com.example.mealcheck.repository;

import com.example.mealcheck.entity.UserRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface UserRestrictionRepository extends JpaRepository<UserRestriction, Long> {
    Optional<UserRestriction> findFirstByUsernameAndRestrictionTypeAndBlockedUntilAfter(
            String username, String restrictionType, LocalDateTime now);

    @Query("""
            select restriction.username from UserRestriction restriction
            where restriction.username in :usernames
              and restriction.restrictionType = :restrictionType
              and restriction.blockedUntil > :now
            """)
    Set<String> findActiveUsernames(@Param("usernames") Collection<String> usernames,
                                    @Param("restrictionType") String restrictionType,
                                    @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            INSERT INTO user_restrictions
                (user_id, username, restriction_type, blocked_until, reason, created_at, updated_at, version)
            VALUES
                (:userId, :username, :restrictionType, :blockedUntil, :reason, :now, :now, 0)
            ON CONFLICT (username, restriction_type) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                blocked_until = GREATEST(user_restrictions.blocked_until, EXCLUDED.blocked_until),
                reason = EXCLUDED.reason,
                updated_at = EXCLUDED.updated_at,
                version = user_restrictions.version + 1
            """, nativeQuery = true)
    int upsert(@Param("userId") Long userId,
               @Param("username") String username,
               @Param("restrictionType") String restrictionType,
               @Param("blockedUntil") LocalDateTime blockedUntil,
               @Param("reason") String reason,
               @Param("now") LocalDateTime now);
}
