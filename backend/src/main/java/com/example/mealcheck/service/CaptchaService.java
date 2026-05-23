package com.example.mealcheck.service;

import com.example.mealcheck.dto.AuthDtos;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CaptchaService {
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, CaptchaChallenge> challenges = new ConcurrentHashMap<>();

    public AuthDtos.CaptchaResponse create() {
        cleanupExpired();
        int left = random.nextInt(8) + 2;
        int right = random.nextInt(8) + 2;
        String id = UUID.randomUUID().toString();
        challenges.put(id, new CaptchaChallenge(String.valueOf(left + right), Instant.now().plus(CAPTCHA_TTL)));
        return new AuthDtos.CaptchaResponse(id, left + " + " + right + " = ?");
    }

    public boolean verify(String id, String answer) {
        if (id == null || id.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        CaptchaChallenge challenge = challenges.remove(id);
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        return challenge.answer().equals(answer.trim());
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record CaptchaChallenge(String answer, Instant expiresAt) {
    }
}
