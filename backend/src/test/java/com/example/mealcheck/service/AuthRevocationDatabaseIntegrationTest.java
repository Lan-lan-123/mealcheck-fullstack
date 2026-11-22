package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.repository.UserAccountRepository;
import com.example.mealcheck.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"mealcheck.analysis.dispatch=database","mealcheck.analysis.worker-enabled=false",
        "mealcheck.mcp.enabled=false","mealcheck.knowledge.auto-index=false","mealcheck.ai.api-key=",
        "mealcheck.upload-dir=logs/backend/auth-cookie-uploads"})
@AutoConfigureMockMvc
@Testcontainers
class AuthRevocationDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void connections(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired UserAccountRepository users;
    @Autowired TokenBlacklistService revocations;
    @MockBean BackgroundTaskDispatcher background;
    @MockBean CaptchaService captcha;
    UserAccount user;
    record Csrf(Cookie cookie,String token) { }
    @org.springframework.web.bind.annotation.RestController
    static class ReplicaProbe {
        @org.springframework.web.bind.annotation.GetMapping("/api/replica/me")
        org.springframework.http.ResponseEntity<?> me() {
            var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if(authentication==null)return org.springframework.http.ResponseEntity.status(401).build();
            return org.springframework.http.ResponseEntity.ok(authentication.getName());
        }
    }
    MockMvc replica() {
        var registries=new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class);
        var filter=new JwtAuthenticationFilter(new JwtService(properties),new CustomUserDetailsService(users),
                new TokenBlacklistService(jdbc),mapper,registries,new AuthenticationCookies(properties));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ReplicaProbe())
                .addFilters(new org.springframework.security.web.context.SecurityContextHolderFilter(
                        new org.springframework.security.web.context.NullSecurityContextRepository()),filter).build();
    }
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE auth_token_revocations,users CASCADE");
        REDIS.execInContainer("redis-cli","FLUSHDB");
        when(captcha.verify("test","3")).thenReturn(true);
        user=new UserAccount();user.setUsername("auth-durable");user.setPasswordHash(passwords.encode("durable-password"));user=users.saveAndFlush(user);
    }
    Cookie responseCookie(org.springframework.mock.web.MockHttpServletResponse response,String name) {
        var value=response.getHeaders("Set-Cookie").stream().filter(v->v.startsWith(name+"=")).findFirst().orElseThrow();
        return new Cookie(name,value.substring(name.length()+1,value.indexOf(';')));
    }
    Csrf csrf(Cookie... cookies) throws Exception {
        var request=get("/api/auth/csrf");
        if(cookies.length>0)request.cookie(cookies);
        var response=http.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        // Existing CSRF cookies need not be rewritten on every read.
        var cookie=Arrays.stream(cookies).filter(c->c.getName().equals("mealcheck_csrf")).findFirst()
                .orElseGet(()->responseCookie(response,"mealcheck_csrf"));
        return new Csrf(cookie,mapper.readTree(response.getContentAsString()).get("token").asText());
    }
    Cookie login() throws Exception {
        var csrf=csrf();
        var response=http.perform(post("/api/auth/login").cookie(csrf.cookie()).header("X-XSRF-TOKEN",csrf.token())
                .contentType("application/json").content("{\"username\":\"auth-durable\",\"password\":\"durable-password\",\"captchaId\":\"test\",\"captchaAnswer\":\"3\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist()).andReturn().getResponse();
        return responseCookie(response,"mealcheck_session");
    }
    @Test void logoutPersistsAcrossRedisLossAndFreshServiceInstances() throws Exception {
        Cookie first=login(),second=login();var csrf=csrf(first);
        var replica=replica();
        replica.perform(get("/api/replica/me").cookie(first)).andExpect(status().isOk());
        http.perform(post("/api/auth/logout").cookie(first,csrf.cookie()).header("X-XSRF-TOKEN",csrf.token()))
                .andExpect(status().isNoContent());
        REDIS.execInContainer("redis-cli","FLUSHDB");
        assertThat(new TokenBlacklistService(jdbc).isBlacklisted(jwt.extractIdentity(first.getValue()).tokenId())).isTrue();
        replica.perform(get("/api/replica/me").cookie(first)).andExpect(status().isUnauthorized());
        replica.perform(get("/api/replica/me").cookie(second)).andExpect(status().isOk());
        http.perform(get("/api/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        http.perform(get("/api/auth/me").cookie(second)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token_revocations",Integer.class)).isEqualTo(1);
    }
    @Test void redisOutageDoesNotDisableExistingCookieAuthenticationOrDurableLogout() throws Exception {
        Cookie session=login();var csrf=csrf(session);
        var docker=REDIS.getDockerClient();String id=REDIS.getContainerId();
        docker.pauseContainerCmd(id).exec();
        try {
            http.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
            http.perform(post("/api/auth/logout").cookie(session,csrf.cookie()).header("X-XSRF-TOKEN",csrf.token()))
                    .andExpect(status().isNoContent());
            http.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        } finally { docker.unpauseContainerCmd(id).exec(); }
    }
    @Test void expiredRevocationsAreCleanedInBoundedBatchesAndGenerationChangesRejectRestoredCredentials() throws Exception {
        var session=login();String generation=properties.getJwt().getGeneration();
        jdbc.update("INSERT INTO auth_token_revocations(token_id,expires_at) SELECT md5(i::text)::uuid,CURRENT_TIMESTAMP-INTERVAL '2 hours' FROM generate_series(1,1100) i");
        revocations.retain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token_revocations",Integer.class)).isEqualTo(100);
        try {
            properties.getJwt().setGeneration("restore-test-v2");
            http.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
            http.perform(get("/api/auth/me").cookie(login())).andExpect(status().isOk());
        } finally { properties.getJwt().setGeneration(generation); }
    }
    @Test void missingCsrfBlocksPersonalSettingsUploadAndLogoutBeforeBusinessChanges() throws Exception {
        var session=login();
        http.perform(put("/api/profile/ai-cost-settings").cookie(session).contentType("application/json").content("{\"version\":0,\"dailyCost\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        http.perform(multipart("/api/meals/analysis-requests").file("image",new byte[]{1,2}).cookie(session))
                .andExpect(status().isForbidden());
        http.perform(post("/api/auth/logout").cookie(session)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_personal_cost_settings",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_token_revocations",Integer.class)).isZero();
    }
}
