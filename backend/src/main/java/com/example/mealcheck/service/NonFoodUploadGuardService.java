package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.dto.NonFoodUploadAlert;
import com.example.mealcheck.entity.NonFoodUploadEvent;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.entity.UserRestriction;
import com.example.mealcheck.repository.NonFoodUploadEventRepository;
import com.example.mealcheck.repository.UserRestrictionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class NonFoodUploadGuardService {
    private static final String COUNT_KEY_PREFIX = "upload:nonfood:count:";
    private static final String ALERT_KEY_PREFIX = "upload:nonfood:alert:";
    private static final String BLOCK_KEY_PREFIX = "upload:nonfood:block:";
    private static final String ALLOW_KEY_PREFIX = "upload:nonfood:allow:";
    private static final Duration ALLOW_CACHE_TTL = Duration.ofSeconds(30);

    private final RedisCacheService redisCacheService;
    private final AppProperties properties;
    private final NonFoodUploadEventRepository eventRepository;
    private final UserRestrictionRepository restrictionRepository;

    public NonFoodUploadGuardService(RedisCacheService redisCacheService,
                                     AppProperties properties,
                                     NonFoodUploadEventRepository eventRepository,
                                     UserRestrictionRepository restrictionRepository) {
        this.redisCacheService = redisCacheService;
        this.properties = properties;
        this.eventRepository = eventRepository;
        this.restrictionRepository = restrictionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NonFoodUploadDecision register(UserAccount user, String reason) {
        int windowMinutes = Math.max(1, properties.getUploadGuard().getNonFoodWindowMinutes());
        int limit = Math.max(1, properties.getUploadGuard().getNonFoodLimit());
        Duration ttl = Duration.ofMinutes(windowMinutes);
        String username = user.getUsername();
        LocalDateTime now = LocalDateTime.now();

        long count = redisCacheService.increment(COUNT_KEY_PREFIX + username, ttl);
        boolean thresholdReached = count >= limit && count > 0;
        int observedMinutes = eventRepository
                .findFirstByUsernameAndCreatedAtAfterOrderByCreatedAtAsc(username, now.minusMinutes(windowMinutes))
                .map(event -> observedMinutes(event.getCreatedAt(), now))
                .orElse(1);

        if (thresholdReached) {
            NonFoodUploadAlert alert = new NonFoodUploadAlert(
                    username,
                    user.getDisplayName(),
                    count,
                    limit,
                    windowMinutes,
                    now
            );
            redisCacheService.setJson(ALERT_KEY_PREFIX + username, alert, ttl);
            redisCacheService.set(BLOCK_KEY_PREFIX + username, "blocked", blockTtl());
            redisCacheService.delete(ALLOW_KEY_PREFIX + username);
            restrictionRepository.upsert(
                    user.getId(),
                    username,
                    UserRestriction.NON_FOOD_UPLOAD,
                    now.plus(blockTtl()),
                    reason == null ? "" : reason,
                    now
            );
        }

        NonFoodUploadEvent event = new NonFoodUploadEvent();
        event.setUser(user);
        event.setUsername(username);
        event.setDisplayName(user.getDisplayName());
        event.setReason(reason == null ? "" : reason);
        event.setWindowCount(count);
        event.setWindowMinutes(windowMinutes);
        event.setObservedMinutes(observedMinutes);
        event.setThresholdReached(thresholdReached);
        eventRepository.save(event);

        return new NonFoodUploadDecision(count, limit, windowMinutes, thresholdReached);
    }

    public List<NonFoodUploadAlert> activeAlerts() {
        return redisCacheService.listJsonByPrefix(
                        ALERT_KEY_PREFIX,
                        new TypeReference<NonFoodUploadAlert>() {}
                )
                .stream()
                .sorted(Comparator.comparing(
                        NonFoodUploadAlert::getLastTriggeredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }

    public boolean isBlocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        if (redisCacheService.get(BLOCK_KEY_PREFIX + username).isPresent()) {
            return true;
        }
        if (redisCacheService.get(ALLOW_KEY_PREFIX + username).isPresent()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return restrictionRepository.findFirstByUsernameAndRestrictionTypeAndBlockedUntilAfter(
                        username, UserRestriction.NON_FOOD_UPLOAD, now)
                .map(restriction -> {
                    Duration remaining = Duration.between(now, restriction.getBlockedUntil());
                    redisCacheService.set(BLOCK_KEY_PREFIX + username, "blocked", remaining);
                    return true;
                })
                .orElseGet(() -> {
                    redisCacheService.set(ALLOW_KEY_PREFIX + username, "allowed", ALLOW_CACHE_TTL);
                    return false;
                });
    }

    public Set<String> activeBlockedUsernames(Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Set.of();
        }
        return restrictionRepository.findActiveUsernames(
                usernames, UserRestriction.NON_FOOD_UPLOAD, LocalDateTime.now());
    }

    public int blockMinutes() {
        return Math.max(1, properties.getUploadGuard().getNonFoodBlockMinutes());
    }

    public int remainingBlockMinutes(String username) {
        return redisCacheService.ttl(BLOCK_KEY_PREFIX + username)
                .or(() -> restrictionRepository.findFirstByUsernameAndRestrictionTypeAndBlockedUntilAfter(
                                username, UserRestriction.NON_FOOD_UPLOAD, LocalDateTime.now())
                        .map(restriction -> Duration.between(LocalDateTime.now(), restriction.getBlockedUntil())))
                .map(duration -> Math.max(1, (int) Math.ceil(duration.getSeconds() / 60.0)))
                .orElse(0);
    }

    private Duration blockTtl() {
        return Duration.ofMinutes(blockMinutes());
    }

    private int observedMinutes(LocalDateTime firstEventAt, LocalDateTime now) {
        long seconds = Math.max(0, Duration.between(firstEventAt, now).getSeconds());
        return Math.max(1, (int) Math.ceil(seconds / 60.0));
    }

    public record NonFoodUploadDecision(long count, int limit, int windowMinutes, boolean thresholdReached) {
    }
}
