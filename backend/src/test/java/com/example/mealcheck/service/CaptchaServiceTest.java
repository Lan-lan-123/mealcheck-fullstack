package com.example.mealcheck.service;

import com.example.mealcheck.dto.AuthDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaServiceTest {

    @Test
    void createStoresAnswerInRedisWithFiveMinuteTtl() {
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.set(anyString(), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(true);
        CaptchaService service = new CaptchaService(redis);

        AuthDtos.CaptchaResponse response = service.create();

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(redis).set(eq("captcha:" + response.getCaptchaId()), answer.capture(), eq(Duration.ofMinutes(5)));
        assertThat(response.getQuestion()).matches("[2-9] \\+ [2-9] = \\?");
        String[] operands = response.getQuestion().split(" ");
        int expectedAnswer = Integer.parseInt(operands[0]) + Integer.parseInt(operands[2]);
        assertThat(answer.getValue()).isEqualTo(String.valueOf(expectedAnswer));
    }

    @Test
    void createFailsClosedWhenRedisWriteFails() {
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.set(anyString(), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(false);
        CaptchaService service = new CaptchaService(redis);

        assertThatThrownBy(service::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("验证码服务暂时不可用，请稍后重试。");
    }

    @Test
    void verifyConsumesChallengeAndAcceptsTrimmedAnswer() {
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.getAndDeleteRequired("captcha:test-id")).thenReturn(Optional.of("12"));
        CaptchaService service = new CaptchaService(redis);

        boolean verified = service.verify(" test-id ", " 12 ");

        assertThat(verified).isTrue();
        verify(redis).getAndDeleteRequired("captcha:test-id");
    }

    @Test
    void verifyRejectsWrongOrExpiredChallenge() {
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.getAndDeleteRequired("captcha:wrong-id")).thenReturn(Optional.of("12"));
        when(redis.getAndDeleteRequired("captcha:expired-id")).thenReturn(Optional.empty());
        CaptchaService service = new CaptchaService(redis);

        assertThat(service.verify("wrong-id", "13")).isFalse();
        assertThat(service.verify("expired-id", "12")).isFalse();
    }

    @Test
    void verifyRejectsBlankInputWithoutCallingRedis() {
        RedisCacheService redis = mock(RedisCacheService.class);
        CaptchaService service = new CaptchaService(redis);

        assertThat(service.verify(" ", "12")).isFalse();
        assertThat(service.verify("test-id", " ")).isFalse();

        verify(redis, never()).getAndDeleteRequired(anyString());
    }

    @Test
    void verifyFailsClosedWhenRedisIsUnavailable() {
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.getAndDeleteRequired("captcha:test-id"))
                .thenThrow(new IllegalStateException("Redis is unavailable."));
        CaptchaService service = new CaptchaService(redis);

        assertThatThrownBy(() -> service.verify("test-id", "12"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("验证码服务暂时不可用，请稍后重试。");
    }
}
