package com.example.mealcheck.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class RedisCacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final int SCAN_BATCH_SIZE = 500;
    private static final long TOKEN_SCALE = 1_000_000L;
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CONSUME_TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refill_tokens = tonumber(ARGV[2])
            local refill_period_ms = tonumber(ARGV[3])
            local request_cost = tonumber(ARGV[4])
            local ttl_ms = tonumber(ARGV[5])
            local scale = tonumber(ARGV[6])

            local redis_time = redis.call('TIME')
            local now_ms = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill_ms')
            local capacity_units = capacity * scale
            local tokens = tonumber(state[1])
            local last_refill_ms = tonumber(state[2])

            if tokens == nil or last_refill_ms == nil then
                tokens = capacity_units
                last_refill_ms = now_ms
            elseif now_ms > last_refill_ms then
                local elapsed_ms = now_ms - last_refill_ms
                local refill_units = math.floor(elapsed_ms * refill_tokens * scale / refill_period_ms)
                tokens = math.min(capacity_units, tokens + refill_units)
                last_refill_ms = now_ms
            end

            local cost_units = request_cost * scale
            local allowed = 0
            local retry_after_ms = 0
            if tokens >= cost_units then
                tokens = tokens - cost_units
                allowed = 1
            else
                local missing_units = cost_units - tokens
                retry_after_ms = math.ceil(missing_units * refill_period_ms / (refill_tokens * scale))
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_ms', last_refill_ms)
            redis.call('PEXPIRE', KEYS[1], ttl_ms)
            return { allowed, math.floor(tokens / scale), retry_after_ms }
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlightLoads = new ConcurrentHashMap<>();

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

    public boolean set(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
            return true;
        } catch (Exception e) {
            log.debug("Redis set failed for key {}: {}", key, e.getMessage());
            return false;
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

    public Optional<String> getRequired(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (Exception e) {
            throw new IllegalStateException("Redis is unavailable.", e);
        }
    }

    public Optional<String> getAndDeleteRequired(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(key));
        } catch (Exception e) {
            throw new IllegalStateException("Redis is unavailable.", e);
        }
    }

    public Optional<Duration> ttl(String key) {
        try {
            Long seconds = redisTemplate.getExpire(key);
            if (seconds == null || seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (Exception e) {
            log.debug("Redis TTL get failed for key {}: {}", key, e.getMessage());
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

    public <T> void setJsonWithJitter(String key, T value, Duration ttl, Duration maxJitter) {
        long baseMillis = Math.max(1L, ttl.toMillis());
        long jitterBound = maxJitter == null ? 0L : Math.max(0L, maxJitter.toMillis());
        long jitterMillis = jitterBound == 0L ? 0L : ThreadLocalRandom.current().nextLong(jitterBound + 1L);
        setJson(key, value, Duration.ofMillis(baseMillis + jitterMillis));
    }

    /**
     * Coalesces concurrent cache misses in this application instance. The first caller loads the
     * value; followers either use a stale value immediately or wait for that same load to finish.
     */
    @SuppressWarnings("unchecked")
    public <T> T singleFlight(String key, Supplier<T> loader, Optional<T> staleFallback) {
        CompletableFuture<Object> candidate = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlightLoads.putIfAbsent(key, candidate);
        if (existing != null) {
            if (staleFallback != null && staleFallback.isPresent()) {
                return staleFallback.get();
            }
            try {
                return (T) existing.join();
            } catch (CompletionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error fatal) {
                    throw fatal;
                }
                throw new IllegalStateException("Single-flight cache load failed", cause);
            }
        }

        try {
            T value = loader.get();
            candidate.complete(value);
            return value;
        } catch (RuntimeException | Error error) {
            candidate.completeExceptionally(error);
            throw error;
        } finally {
            inFlightLoads.remove(key, candidate);
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
            redisTemplate.execute((RedisCallback<Long>) connection -> {
                long deleted = 0L;
                List<byte[]> batch = new ArrayList<>(SCAN_BATCH_SIZE);
                ScanOptions options = scanOptions(prefix);
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() == SCAN_BATCH_SIZE) {
                            deleted += connection.keyCommands().del(batch.toArray(byte[][]::new));
                            batch.clear();
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    deleted += connection.keyCommands().del(batch.toArray(byte[][]::new));
                }
                return deleted;
            });
        } catch (Exception e) {
            log.debug("Redis prefix delete failed for prefix {}: {}", prefix, e.getMessage());
        }
    }

    public <T> List<T> listJsonByPrefix(String prefix, TypeReference<T> typeReference) {
        try {
            List<String> keys = scanKeys(prefix);
            if (keys.isEmpty()) {
                return List.of();
            }

            List<T> values = new ArrayList<>();
            for (String key : keys) {
                getJson(key, typeReference).ifPresent(values::add);
            }
            return values;
        } catch (Exception e) {
            log.debug("Redis prefix JSON get failed for prefix {}: {}", prefix, e.getMessage());
            return List.of();
        }
    }

    public long increment(String key, Duration ttl) {
        try {
            long ttlMillis = Math.max(1L, ttl.toMillis());
            Long value = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key), String.valueOf(ttlMillis));
            return value == null ? 0L : value;
        } catch (RedisConnectionFailureException e) {
            log.debug("Redis increment skipped because Redis is unavailable: {}", e.getMessage());
            return -1L;
        } catch (Exception e) {
            log.debug("Redis increment failed for key {}: {}", key, e.getMessage());
            return -1L;
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<TokenBucketResult> consumeTokenBucket(String key,
                                                           long capacity,
                                                           long refillTokens,
                                                           Duration refillPeriod,
                                                           long requestCost) {
        validateTokenBucket(capacity, refillTokens, refillPeriod, requestCost);
        try {
            long refillPeriodMillis = Math.max(1L, refillPeriod.toMillis());
            long ttlMillis = tokenBucketTtlMillis(capacity, refillTokens, refillPeriodMillis);
            List<Long> result = redisTemplate.execute(
                    CONSUME_TOKEN_BUCKET,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillTokens),
                    String.valueOf(refillPeriodMillis),
                    String.valueOf(requestCost),
                    String.valueOf(ttlMillis),
                    String.valueOf(TOKEN_SCALE)
            );
            if (result == null || result.size() < 3) {
                return Optional.empty();
            }
            return Optional.of(new TokenBucketResult(
                    result.get(0) == 1L,
                    Math.max(0L, result.get(1)),
                    Duration.ofMillis(Math.max(0L, result.get(2)))
            ));
        } catch (Exception e) {
            log.debug("Redis token bucket failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> scanKeys(String prefix) {
        List<String> keys = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
            List<String> matches = new ArrayList<>();
            ScanOptions options = scanOptions(prefix);
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                cursor.forEachRemaining(rawKey -> matches.add(new String(rawKey, java.nio.charset.StandardCharsets.UTF_8)));
            }
            return matches;
        });
        return keys == null ? List.of() : keys;
    }

    private ScanOptions scanOptions(String prefix) {
        return ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(SCAN_BATCH_SIZE)
                .build();
    }

    private void validateTokenBucket(long capacity,
                                     long refillTokens,
                                     Duration refillPeriod,
                                     long requestCost) {
        if (capacity <= 0L || refillTokens <= 0L || requestCost <= 0L || requestCost > capacity
                || refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Token bucket values must be positive and request cost must not exceed capacity.");
        }
    }

    private long tokenBucketTtlMillis(long capacity, long refillTokens, long refillPeriodMillis) {
        double fullRefillMillis = Math.ceil((double) capacity * refillPeriodMillis / refillTokens);
        double ttlMillis = Math.max(refillPeriodMillis, fullRefillMillis * 2.0d);
        return ttlMillis >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, (long) ttlMillis);
    }

    public record TokenBucketResult(boolean allowed, long remainingTokens, Duration retryAfter) {
    }
}
