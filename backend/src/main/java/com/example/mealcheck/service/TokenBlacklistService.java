package com.example.mealcheck.service;

import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenBlacklistService {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public TokenBlacklistService(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Callers must obtain tokenId from a JWT whose signature and claims were verified.
    @org.springframework.transaction.annotation.Transactional(timeout=3)
    public void blacklist(String tokenId, Duration ttl) {
        if (tokenId == null || tokenId.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        jdbc.update("INSERT INTO auth_token_revocations(token_id,expires_at) VALUES (?,CURRENT_TIMESTAMP+(? * INTERVAL '1 millisecond')) "
                +"ON CONFLICT(token_id) DO UPDATE SET expires_at=GREATEST(auth_token_revocations.expires_at,EXCLUDED.expires_at)",
                java.util.UUID.fromString(tokenId),ttl.toMillis());
    }

    public boolean isBlacklisted(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM auth_token_revocations WHERE token_id=? AND expires_at>CURRENT_TIMESTAMP)",
                Boolean.class,java.util.UUID.fromString(tokenId)));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${mealcheck.jwt.revocation-cleanup-ms:3600000}")
    public void retain() {
        try { jdbc.update("DELETE FROM auth_token_revocations WHERE token_id IN (SELECT token_id FROM auth_token_revocations "
                +"WHERE expires_at<CURRENT_TIMESTAMP-INTERVAL '1 hour' ORDER BY expires_at LIMIT 1000)"); }
        catch(org.springframework.dao.DataAccessException unavailable) {
            org.slf4j.LoggerFactory.getLogger(TokenBlacklistService.class).warn("Token revocation cleanup deferred type={}",unavailable.getClass().getSimpleName());
        }
    }
    public static class UnavailableException extends RuntimeException {
        public UnavailableException() { super("退出尚未完成，认证存储暂时不可用，请稍后重试。"); }
    }
}
