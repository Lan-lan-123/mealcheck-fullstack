package com.example.mealcheck.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Service
public class RedisCacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isReachable() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.debug("Redis set failed for key {}: {}", key, e.getMessage());
        }
    }

    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (Exception e) {
            log.debug("Redis get failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public <T> void setJson(String key, T value, Duration ttl) {
        try {
            set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("Redis JSON set failed for key {}: {}", key, e.getMessage());
        }
    }

    public <T> Optional<T> getJson(String key, TypeReference<T> typeReference) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, typeReference));
        } catch (Exception e) {
            log.debug("Redis JSON get failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("Redis delete failed for key {}: {}", key, e.getMessage());
        }
    }

    public void deleteByPrefix(String prefix) {
        try {
            Set<String> keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.debug("Redis prefix delete failed for prefix {}: {}", prefix, e.getMessage());
        }
    }

    public long increment(String key, Duration ttl) {
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redisTemplate.expire(key, ttl);
            }
            return value == null ? 0L : value;
        } catch (RedisConnectionFailureException e) {
            log.debug("Redis increment skipped because Redis is unavailable: {}", e.getMessage());
            return 0L;
        } catch (Exception e) {
            log.debug("Redis increment failed for key {}: {}", key, e.getMessage());
            return 0L;
        }
    }
}
