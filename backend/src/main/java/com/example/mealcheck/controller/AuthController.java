package com.example.mealcheck.controller;

import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.security.JwtService;
import com.example.mealcheck.service.AuthService;
import com.example.mealcheck.service.RateLimitExceededException;
import com.example.mealcheck.service.RateLimitService;
import com.example.mealcheck.service.TokenBlacklistService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.example.mealcheck.security.AuthenticationCookies;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final AuthenticationCookies cookies;
    private final CookieCsrfTokenRepository csrf;

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          TokenBlacklistService tokenBlacklistService,
                          RateLimitService rateLimitService, AuthenticationCookies cookies, CookieCsrfTokenRepository csrf) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.rateLimitService = rateLimitService;
        this.cookies=cookies;this.csrf=csrf;
    }

    @GetMapping("/csrf")
    public ResponseEntity<java.util.Map<String,String>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(java.util.Map.of("token",token.getToken(),"headerName",token.getHeaderName()));
    }
    @GetMapping("/me")
    public ResponseEntity<AuthDtos.AuthResponse> me(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new AuthDtos.AuthResponse(null,user.getUsername(),user.getDisplayName(),user.getRole()));
    }

    @GetMapping("/captcha")
    public AuthDtos.CaptchaResponse captcha(HttpServletRequest servletRequest) {
        enforce(rateLimitService.consumeCaptcha(servletRequest.getRemoteAddr()), "验证码请求太频繁，请稍后再试。");
        return authService.captcha();
    }

    @PostMapping("/register")
    public AuthDtos.AuthResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                          HttpServletRequest servletRequest,HttpServletResponse response) {
        enforce(rateLimitService.consumeRegistration(servletRequest.getRemoteAddr()), "注册请求太频繁，请稍后再试。");
        return authenticated(authService.register(request),servletRequest,response);
    }

    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                       HttpServletRequest servletRequest,HttpServletResponse response) {
        enforce(rateLimitService.consumeLogin(servletRequest.getRemoteAddr(), request.getUsername()), "登录尝试太频繁，请稍后再试。");
        return authenticated(authService.login(request),servletRequest,response);
    }

    private AuthDtos.AuthResponse authenticated(AuthDtos.AuthResponse result,HttpServletRequest request,HttpServletResponse response) {
        cookies.write(response,result.getToken(),jwtService.remainingTtl(result.getToken()));
        csrf.saveToken(null,request,response);
        return result;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response) {
        String token=cookies.read(request);
        String authorization=request.getHeader("Authorization");
        if(token==null && authorization!=null && authorization.startsWith("Bearer "))token=authorization.substring(7);
        if (token != null) {
            java.time.Duration ttl;
            JwtService.TokenIdentity identity;
            try {
                identity = jwtService.extractIdentity(token);
                ttl = jwtService.remainingTtl(token);
            } catch (Exception ignored) {
                // Invalid or expired tokens are already unusable.
                cookies.clear(response);csrf.saveToken(null,request,response);
                return ResponseEntity.noContent().build();
            }
            try { tokenBlacklistService.blacklist(identity.tokenId(), ttl); }
            catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
                throw new TokenBlacklistService.UnavailableException();
            }
        }
        cookies.clear(response);csrf.saveToken(null,request,response);
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
