package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.config.AppProperties.Ai.Connection;
import com.example.mealcheck.security.UserPrincipal;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

/** Durable overrides of environment defaults. No API ever returns a stored credential. */
@Service
public class AiSettingsService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiSettingsService.class);
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final AdminAuditService audit;
    private final SecretKeySpec encryptionKey;
    private volatile State applied;
    public record View(String model, String baseUrl, boolean apiKeyConfigured, String source,
                       long version, OffsetDateTime updatedAt) { }
    public record Update(@NotBlank @Size(max=200) String model, @NotBlank @Size(max=1000) String baseUrl,
                         @Size(max=4096) String apiKey, @Min(0) long version) {
        @Override public String toString() { return "AI settings update [credentials redacted]"; }
    }
    public record Version(@Min(0) long version) { }
    private record State(Connection connection, long version, OffsetDateTime updatedAt) {
        @Override public String toString() { return "AI settings state [credentials redacted]"; }
    }

    public AiSettingsService(JdbcTemplate jdbc, AppProperties properties, AdminAuditService audit,
            @Value("${mealcheck.ai-settings.encryption-key:}") String secret) {
        this.jdbc=jdbc; this.properties=properties; this.audit=audit;
        try {
            String material = secret == null || secret.isBlank() ? properties.getJwt().getSecret() : secret;
            encryptionKey = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(("mealcheck-ai-settings-v1:" + material).getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception error) { throw new IllegalStateException("Cannot initialize AI settings encryption"); }
    }

    @PostConstruct public void initialize() { refresh(); }

    @Scheduled(fixedDelay=2000, initialDelay=2000)
    public void synchronize() {
        try { refresh(); }
        catch (RuntimeException unavailable) { log.warn("AI settings refresh unavailable; retaining the last applied version"); }
    }

    public View view() { return describe(refresh()); }

    private State refresh() {
        State state = read(false);
        publish(state);
        return state;
    }
    private State read(boolean lock) {
        return jdbc.queryForObject("SELECT model,base_url,api_key_ciphertext,version,updated_at FROM ai_connection_settings WHERE id=1"
                + (lock ? " FOR UPDATE" : ""), (rs,n) -> new State(rs.getString("model") == null ? null
                : new Connection(decrypt(rs.getString("api_key_ciphertext")), rs.getString("base_url"), rs.getString("model")),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class)));
    }
    private synchronized void publish(State state) {
        if (applied == null || state.version() >= applied.version()) {
            properties.getAi().overrideConnection(state.connection());
            applied = state;
        }
    }
    private void publishAfterCommit(State state) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(state); }
            });
        } else publish(state);
    }
    private View describe(State state) {
        Connection connection = state.connection() == null ? properties.getAi().environmentConnection() : state.connection();
        return new View(connection.model(), connection.baseUrl(), connection.apiKey() != null && !connection.apiKey().isBlank(),
                state.connection() == null ? "ENVIRONMENT" : "ADMIN", state.version(), state.updatedAt());
    }

    @Transactional
    public View save(Update request, UserPrincipal principal) {
        String model = request.model().trim();
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")) bad("模型名称包含无效字符");
        String url = normalizeUrl(request.baseUrl());
        State previous = read(true);
        checkVersion(request.version(), previous);
        Connection old = previous.connection() == null ? properties.getAi().environmentConnection() : previous.connection();
        String key = request.apiKey() == null ? "" : request.apiKey().trim();
        if (key.isBlank()) {
            if (!sameOrigin(old.baseUrl(), url)) bad("更换 API 服务地址时，请同时填写对应的 API Key");
            key = old.apiKey();
        }
        if (key == null || key.isBlank() || key.length()>4096 || key.chars().anyMatch(Character::isWhitespace))
            bad("请填写有效的 API Key");
        jdbc.update("UPDATE ai_connection_settings SET model=?,base_url=?,api_key_ciphertext=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                model, url, encrypt(key));
        State saved = read(false);
        audit.log(principal, "UPDATE_AI_SETTINGS", "AI_SETTINGS", 1L, "更新 AI 连接配置");
        publishAfterCommit(saved);
        return describe(saved);
    }

    @Transactional
    public View reset(Version request, UserPrincipal principal) {
        State previous = read(true);
        checkVersion(request.version(), previous);
        jdbc.update("UPDATE ai_connection_settings SET model=NULL,base_url=NULL,api_key_ciphertext=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1");
        State saved = read(false);
        audit.log(principal, "RESET_AI_SETTINGS", "AI_SETTINGS", 1L, "恢复部署中的 AI 连接配置");
        publishAfterCommit(saved);
        return describe(saved);
    }
    private void checkVersion(long version, State state) {
        if (version != state.version()) throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被修改，请刷新后重试");
    }
    static String normalizeUrl(String input) {
        try {
            URI uri = URI.create(input.trim());
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException();
            String url = uri.toString().replaceAll("/+$", "");
            for (String suffix : java.util.List.of("/chat/completions", "/embeddings"))
                if (url.endsWith(suffix)) url = url.substring(0,url.length()-suffix.length());
            return url;
        } catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base URL 须为不含账号、查询参数或片段的 HTTP(S) 地址"); }
    }
    private static boolean sameOrigin(String left, String right) {
        try {
            URI a=URI.create(left), b=URI.create(right);
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                    && port(a)==port(b);
        } catch (RuntimeException invalid) { return false; }
    }
    private static int port(URI uri) { return uri.getPort()<0 ? ("https".equals(uri.getScheme())?443:80) : uri.getPort(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private String encrypt(String value) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,encryptionKey,new GCMParameterSpec(128,nonce));
            return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot encrypt AI settings"); }
    }
    private String decrypt(String value) {
        try {
            String[] parts=value.split(":",2);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,encryptionKey,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new IllegalStateException("Cannot decrypt AI settings; verify the settings encryption key"); }
    }
}
