package com.example.mealcheck.controller;

import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.security.JwtService;
import com.example.mealcheck.service.AuthService;
import com.example.mealcheck.service.RateLimitExceededException;
import com.example.mealcheck.service.RateLimitService;
import com.example.mealcheck.service.TokenBlacklistService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RateLimitService rateLimitService;

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          TokenBlacklistService tokenBlacklistService,
                          RateLimitService rateLimitService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/captcha")
    public AuthDtos.CaptchaResponse captcha(HttpServletRequest servletRequest) {
        enforce(rateLimitService.consumeCaptcha(servletRequest.getRemoteAddr()), "验证码请求太频繁，请稍后再试。");
        return authService.captcha();
    }

    @PostMapping("/register")
    public AuthDtos.AuthResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                          HttpServletRequest servletRequest) {
        enforce(rateLimitService.consumeRegistration(servletRequest.getRemoteAddr()), "注册请求太频繁，请稍后再试。");
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                       HttpServletRequest servletRequest) {
        enforce(rateLimitService.consumeLogin(servletRequest.getRemoteAddr(), request.getUsername()), "登录尝试太频繁，请稍后再试。");
        return authService.login(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            java.time.Duration ttl;
            try {
                ttl = jwtService.remainingTtl(token);
            } catch (Exception ignored) {
                // Invalid or expired tokens are already unusable.
                return ResponseEntity.noContent().build();
            }
            tokenBlacklistService.blacklist(token, ttl);
        }
        return ResponseEntity.noContent().build();
    }

    private void enforce(RateLimitService.RateLimitDecision decision, String message) {
        if (decision.unavailable()) {
            throw new IllegalStateException("认证限流服务暂时不可用，请稍后重试。");
        }
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    message, decision.limit(), decision.remaining(), decision.retryAfter());
        }
    }
}
