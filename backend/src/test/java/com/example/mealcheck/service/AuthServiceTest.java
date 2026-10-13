package com.example.mealcheck.service;

import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    @Test void existingUsernameHasAnExplicitConflict() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        CaptchaService captcha = mock(CaptchaService.class);
        when(captcha.verify("id", "4")).thenReturn(true);
        when(users.existsByUsername("alice")).thenReturn(true);
        var service = new AuthService(users, mock(PasswordEncoder.class), mock(AuthenticationManager.class), mock(JwtService.class), captcha);
        var request = new AuthDtos.RegisterRequest(); request.setUsername("alice"); request.setCaptchaId("id"); request.setCaptchaAnswer("4");
        assertThatThrownBy(() -> service.register(request)).isInstanceOf(UsernameConflictException.class);
        verify(users, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test void unrelatedDatabaseConstraintsAreNotMisreportedAsUsernameConflicts() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        CaptchaService captcha = mock(CaptchaService.class);
        when(captcha.verify("id", "4")).thenReturn(true);
        var violation = new org.springframework.dao.DataIntegrityViolationException("private",
                new org.hibernate.exception.ConstraintViolationException("private", new java.sql.SQLException(), "other_constraint"));
        org.mockito.Mockito.doThrow(violation).when(users).saveAndFlush(org.mockito.ArgumentMatchers.any());
        var service = new AuthService(users, mock(PasswordEncoder.class), mock(AuthenticationManager.class), mock(JwtService.class), captcha);
        var request = new AuthDtos.RegisterRequest(); request.setUsername("alice"); request.setPassword("long-password");
        request.setCaptchaId("id"); request.setCaptchaAnswer("4");
        assertThatThrownBy(() -> service.register(request)).isSameAs(violation);
    }

    @Test
    void registrationRequiresAValidCaptchaBeforeCheckingTheUsername() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        CaptchaService captcha = mock(CaptchaService.class);
        AuthService service = new AuthService(
                users,
                mock(PasswordEncoder.class),
                mock(AuthenticationManager.class),
                mock(JwtService.class),
                captcha);
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest();
        request.setUsername("new-user");
        request.setPassword("long-password");
        request.setCaptchaId("captcha-id");
        request.setCaptchaAnswer("12");
        when(captcha.verify("captcha-id", "12")).thenReturn(false);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BadCredentialsException.class);
        verify(users, never()).existsByUsername("new-user");
    }
}
