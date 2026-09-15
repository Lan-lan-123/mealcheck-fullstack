package com.example.mealcheck.service;

import com.example.mealcheck.dto.AuthDtos;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

@Service
public class CaptchaService {
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final String CAPTCHA_KEY_PREFIX = "captcha:";

    private final SecureRandom random = new SecureRandom();
    private final RedisCacheService redisCacheService;

    public CaptchaService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    public AuthDtos.CaptchaResponse create() {
        int left = random.nextInt(8) + 2;
        int right = random.nextInt(8) + 2;
        String id = UUID.randomUUID().toString();
        boolean stored = redisCacheService.set(
                CAPTCHA_KEY_PREFIX + id,
                String.valueOf(left + right),
                CAPTCHA_TTL
        );
        if (!stored) {
            throw new IllegalStateException("验证码服务暂时不可用，请稍后重试。");
        }
        return new AuthDtos.CaptchaResponse(id, left + " + " + right + " = ?");
    }

    public boolean verify(String id, String answer) {
        if (id == null || id.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        try {
            return redisCacheService.getAndDeleteRequired(CAPTCHA_KEY_PREFIX + id.trim())
                    .map(expectedAnswer -> expectedAnswer.equals(answer.trim()))
                    .orElse(false);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("验证码服务暂时不可用，请稍后重试。", e);
        }
    }
}
