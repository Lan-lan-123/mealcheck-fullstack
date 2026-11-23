package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

/** Connection rows are immutable; jobs retain their original credentials and model. */
@Service
public class KnowledgeEmbeddingProfileStore {
    public record Profile(String model, String baseUrl, String apiKey, int dimension, boolean sendDimensions) {
        public boolean configured() { return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank(); }
        @Override public String toString() { return "Embedding profile [credentials redacted]"; }
    }
    public record Stored(long id, Profile profile, String source, OffsetDateTime testedAt) {
        @Override public String toString() { return "Stored embedding profile [credentials redacted]"; }
    }
    private final JdbcTemplate jdbc;
    private final SecretKeySpec key;
    public KnowledgeEmbeddingProfileStore(JdbcTemplate jdbc, AppProperties properties,
            @Value("${mealcheck.ai-settings.encryption-key:}") String secret) {
        this.jdbc = jdbc;
        try {
            String material = secret == null || secret.isBlank() ? properties.getJwt().getSecret() : secret;
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(("mealcheck-embedding-settings-v1:" + material).getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception failure) { throw new IllegalStateException("Cannot initialize embedding settings encryption"); }
    }
    public Stored active() { return reference("active_profile_id"); }
    public Stored draft() { return reference("draft_profile_id"); }
    private Stored reference(String column) {
        var ids = jdbc.queryForList("SELECT " + column + " FROM knowledge_embedding_settings WHERE id=1", Long.class);
        return ids.isEmpty() || ids.get(0) == null ? null : find(ids.get(0));
    }
    public Stored find(long id) {
        return jdbc.queryForObject("SELECT * FROM knowledge_embedding_profiles WHERE id=?", (rs, n) -> new Stored(
                rs.getLong("id"), new Profile(rs.getString("model"), rs.getString("base_url"),
                decrypt(rs.getString("api_key_ciphertext")), rs.getInt("dimension"), rs.getBoolean("send_dimensions")),
                rs.getString("source"), rs.getObject("tested_at", OffsetDateTime.class)), id);
    }
    public long insert(Profile profile, String source) {
        return jdbc.queryForObject("INSERT INTO knowledge_embedding_profiles(model,base_url,api_key_ciphertext,dimension,send_dimensions,source) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, profile.model(), profile.baseUrl(), encrypt(profile.apiKey()), profile.dimension(), profile.sendDimensions(), source);
    }
    private String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot encrypt embedding settings"); }
    }
    private String decrypt(String value) {
        try {
            String[] parts = value.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new IllegalStateException("Cannot decrypt embedding settings; verify the settings encryption key"); }
    }
}
