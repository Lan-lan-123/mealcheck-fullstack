package com.example.mealcheck.security;

import com.example.mealcheck.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final AppProperties properties;
    private final SecretKey secretKey;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        if(properties.getJwt().getGeneration()==null || !properties.getJwt().getGeneration().matches("[A-Za-z0-9._-]{1,100}"))
            throw new IllegalArgumentException("Invalid authentication generation");
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            secret = "mealcheck-jwt-secret-change-me-please-32chars";
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getExpirationMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(principal.getUsername())
                .claim("uid", principal.getId())
                .claim("ver", principal.getTokenVersion())
                .claim("auth_gen", properties.getJwt().getGeneration())
                .claim("displayName", principal.getDisplayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public TokenIdentity extractIdentity(String token) {
        Claims claims = parseClaims(token);
        Long userId = claims.get("uid", Long.class);
        String username = claims.getSubject();
        String tokenId = claims.getId();
        Long tokenVersion = claims.get("ver", Long.class);
        if (userId == null || userId <= 0 || username == null || username.isBlank()
                || tokenId == null || tokenId.length() != 36
                || tokenVersion == null || tokenVersion < 0 || claims.getExpiration() == null
                || !properties.getJwt().getGeneration().equals(claims.get("auth_gen",String.class))) {
            throw new IllegalArgumentException("Token identity or expiration is missing");
        }
        if (!UUID.fromString(tokenId).toString().equals(tokenId)) {
            throw new IllegalArgumentException("Invalid token ID");
        }
        return new TokenIdentity(userId, username, tokenId, tokenVersion);
    }

    public record TokenIdentity(Long userId, String username, String tokenId, long tokenVersion) {}

    public Duration remainingTtl(String token) {
        Date expiration = parseClaims(token).getExpiration();
        if (expiration == null) {
            return Duration.ZERO;
        }
        return Duration.between(Instant.now(), expiration.toInstant());
    }

    private Claims parseClaims(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims;
    }
}
