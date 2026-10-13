package com.example.mealcheck.service;

import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.JwtService;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final CaptchaService captchaService;

    public AuthService(UserAccountRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       CaptchaService captchaService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
    }

    public AuthDtos.CaptchaResponse captcha() {
        return captchaService.create();
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        if (!captchaService.verify(request.getCaptchaId(), request.getCaptchaAnswer())) {
            throw new BadCredentialsException("验证码错误或已过期");
        }
        String username = request.getUsername().trim();
        if (username.length() < 3 || username.length() > 64) {
            throw new IllegalArgumentException("用户名必须为 3 到 64 个字符");
        }
        if (userRepository.existsByUsername(username)) {
            throw new UsernameConflictException();
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? username : request.getDisplayName().trim());
        try {
            // Flush inside this transaction so the unique constraint race is translated here.
            userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "users_username_key".equals(violation.getConstraintName())) {
                    throw new UsernameConflictException();
                }
            }
            throw conflict;
        }
        UserPrincipal principal = new UserPrincipal(user);
        return new AuthDtos.AuthResponse(
                jwtService.generateToken(principal),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        if (!captchaService.verify(request.getCaptchaId(), request.getCaptchaAnswer())) {
            throw new BadCredentialsException("验证码错误或已过期");
        }
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        String expectedRole = request.getExpectedRole() == null ? "" : request.getExpectedRole().trim().toUpperCase();
        if (!expectedRole.isBlank() && !principal.getRole().equalsIgnoreCase(expectedRole)) {
            throw new BadCredentialsException("登录入口与账号角色不匹配");
        }
        return new AuthDtos.AuthResponse(
                jwtService.generateToken(principal),
                principal.getUsername(),
                principal.getDisplayName(),
                principal.getRole()
        );
    }
}
