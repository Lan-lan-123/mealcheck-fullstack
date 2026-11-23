package com.example.mealcheck.controller;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.config.SecurityConfig;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.*;
import com.example.mealcheck.service.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest({AuthController.class, AuthLifecycleHttpTest.ProbeController.class})
@Import({SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class, CustomUserDetailsService.class,
        AuthService.class, TokenBlacklistService.class, AuthLifecycleHttpTest.ProbeController.class})
@EnableConfigurationProperties(AppProperties.class)
class AuthLifecycleHttpTest {
    @RestController
    static class ProbeController {
        @GetMapping("/api/test/authenticated") public String authenticated() { return "ok"; }
    }

    @Autowired MockMvc http;
    @Autowired JwtService jwt;
    @Autowired AppProperties properties;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @MockitoBean UserAccountRepository users;
    @MockitoBean RedisCacheService redis;
    @MockitoBean org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean CaptchaService captcha;
    @MockitoBean RateLimitService limits;
    UserAccount user;
    Map<String, String> revoked;

    @BeforeEach void setup() {
        user = new UserAccount(); user.setId(10L); user.setUsername("alice");
        user.setPasswordHash(passwords.encode("long-password"));
        when(users.findById(10L)).thenReturn(Optional.of(user));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(captcha.verify("captcha", "4")).thenReturn(true);
        when(limits.consumeLogin(anyString(), eq("alice"))).thenReturn(new RateLimitService.RateLimitDecision(
                RateLimitService.RateLimitStatus.ALLOWED, 10, 9, Duration.ZERO));
        revoked = new HashMap<>();
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(UUID.class))).thenAnswer(call -> revoked.containsKey(call.getArgument(2).toString()));
        when(jdbc.update(startsWith("INSERT INTO auth_token_revocations"),any(UUID.class),anyLong())).thenAnswer(call -> {
            assertThat(call.<Long>getArgument(2)).isPositive();
            revoked.put(call.getArgument(1).toString(), "1");
            return 1;
        });
    }

    @Test void repeatedIssuanceCreatesIndependentCredentialsAndLogoutRevokesOnlyOne() throws Exception {
        String first = jwt.generateToken(new UserPrincipal(user));
        String second = jwt.generateToken(new UserPrincipal(user));
        String firstId = jwt.extractIdentity(first).tokenId();
        assertThat(first).isNotEqualTo(second);
        assertThat(firstId).isNotEqualTo(jwt.extractIdentity(second).tokenId());
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk());
        for (int i = 0; i < 2; i++) http.perform(post("/api/auth/logout").with(csrf()).cookie(new jakarta.servlet.http.Cookie("mealcheck_session",first)))
                .andExpect(status().isNoContent());
        assertThat(revoked).containsOnlyKeys(firstId);
        verifyNoInteractions(redis);
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }

    @Test void bannedAccountCannotLoginAndUnbanningDoesNotRestoreOldTokens() throws Exception {
        String old = login();
        user.setEnabled(false); user.invalidateTokens();
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginPayload()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));
        user.setEnabled(true); user.invalidateTokens();
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        String fresh = login();
        assertThat(jwt.extractIdentity(fresh).tokenVersion()).isEqualTo(2);
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk());
    }

    @Test void malformedOrLegacyClaimsCannotAuthenticateOrWriteRevocationEntries() throws Exception {
        var key = Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        String[][] invalidClaims = {{"", "0"}, {"bad-id", "0"},
                {UUID.randomUUID().toString(), null}, {UUID.randomUUID().toString(), "-1"}};
        for (String[] claims : invalidClaims) {
            String tokenId = claims[0];
            var builder = Jwts.builder().subject("alice").claim("uid", 10L)
                    .expiration(Date.from(Instant.now().plusSeconds(60)));
            if (!tokenId.isEmpty()) builder.id(tokenId);
            if (claims[1] != null) builder.claim("ver", Long.parseLong(claims[1]));
            String invalid = builder.signWith(key).compact();
            http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
            http.perform(post("/api/auth/logout").with(csrf()).header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isNoContent());
        }
        verify(jdbc,never()).update(startsWith("INSERT INTO auth_token_revocations"),any(UUID.class),anyLong());
        assertThat(revoked).isEmpty();
    }

    @Test void unavailableRevocationStoreFailsClosedAndLogoutCanBeRetried() throws Exception {
        String token = jwt.generateToken(new UserPrincipal(user));
        when(jdbc.update(startsWith("INSERT INTO auth_token_revocations"),any(UUID.class),anyLong()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        http.perform(post("/api/auth/logout").with(csrf()).header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_DEPENDENCY_UNAVAILABLE"));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(UUID.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_DEPENDENCY_UNAVAILABLE"));
        assertThat(revoked).isEmpty();
        doAnswer(call -> revoked.containsKey(call.getArgument(2).toString())).when(jdbc).queryForObject(anyString(),eq(Boolean.class),any(UUID.class));
        when(jdbc.update(startsWith("INSERT INTO auth_token_revocations"),any(UUID.class),anyLong())).thenAnswer(call -> {
            revoked.put(call.getArgument(1).toString(), "1");
            return 1;
        });
        http.perform(post("/api/auth/logout").with(csrf()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        http.perform(get("/api/test/authenticated").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        var response = http.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginPayload()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        String cookie=response.getHeaders("Set-Cookie").stream().filter(v->v.startsWith("mealcheck_session=")).findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly","SameSite=Lax","Path=/");
        return cookie.substring("mealcheck_session=".length(),cookie.indexOf(';'));
    }

    @Test void missingAndInvalidCsrfCannotLoginLogoutOrMutateEvenWithAValidCookie() throws Exception {
        String token=jwt.generateToken(new UserPrincipal(user));
        http.perform(post("/api/auth/login").contentType("application/json").content(loginPayload()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        http.perform(post("/api/auth/logout").cookie(new jakarta.servlet.http.Cookie("mealcheck_session",token)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/auth/logout").with(csrf().useInvalidToken()).cookie(new jakarta.servlet.http.Cookie("mealcheck_session",token)))
                .andExpect(status().isForbidden());
        assertThat(revoked).isEmpty();
    }
    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    void realCsrfBootstrapAndCookieLoginWorkAndCrossOriginRequestsAreRejected() throws Exception {
        var response=http.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String csrfToken=mapper.readTree(response.getContentAsString()).get("token").asText();
        String cookie=response.getHeaders("Set-Cookie").stream().filter(v->v.startsWith("mealcheck_csrf=")).findFirst().orElseThrow();
        var csrfCookie=new jakarta.servlet.http.Cookie("mealcheck_csrf",cookie.substring("mealcheck_csrf=".length(),cookie.indexOf(';')));
        var login=http.perform(post("/api/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN",csrfToken)
                .contentType("application/json").content(loginPayload())).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist()).andReturn().getResponse();
        String auth=login.getHeaders("Set-Cookie").stream().filter(v->v.startsWith("mealcheck_session=")).findFirst().orElseThrow();
        var authCookie=new jakarta.servlet.http.Cookie("mealcheck_session",auth.substring("mealcheck_session=".length(),auth.indexOf(';')));
        http.perform(get("/api/auth/me").cookie(authCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        http.perform(post("/api/auth/logout").with(csrf()).cookie(authCookie).header("Origin","https://untrusted.test"))
                .andExpect(status().isForbidden());
    }
    @Test void secureProductionCookiesAndGenerationChangesInvalidateExistingTokens() throws Exception {
        String old=jwt.generateToken(new UserPrincipal(user));
        String generation=properties.getJwt().getGeneration();
        try {
            properties.getJwt().setGeneration("restored-20261026");properties.getJwt().setCookieSecure(true);
            http.perform(get("/api/test/authenticated").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
            var response=http.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginPayload()))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getHeaders("Set-Cookie")).anySatisfy(value->assertThat(value)
                    .startsWith("__Host-mealcheck_session=").contains("Secure","HttpOnly","SameSite=Lax","Path=/").doesNotContain("Domain="));
        } finally { properties.getJwt().setGeneration(generation);properties.getJwt().setCookieSecure(false); }
    }

    private String loginPayload() {
        return "{\"username\":\"alice\",\"password\":\"long-password\",\"captchaId\":\"captcha\",\"captchaAnswer\":\"4\"}";
    }
}
