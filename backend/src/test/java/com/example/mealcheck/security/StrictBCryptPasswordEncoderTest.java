package com.example.mealcheck.security;

import com.example.mealcheck.dto.AuthDtos;
import com.example.mealcheck.dto.DeleteAccountRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class StrictBCryptPasswordEncoderTest {
    private final StrictBCryptPasswordEncoder encoder = new StrictBCryptPasswordEncoder();

    @Test void preservesExistingShortPasswordHashes() {
        String legacy = new BCryptPasswordEncoder().encode("existing-password");
        assertThat(encoder.matches("existing-password", legacy)).isTrue();
        assertThat(encoder.matches("wrong-password", legacy)).isFalse();
    }

    @Test void rejectsPasswordsThatPreviouslyAliasedBySuffix() {
        String prefix = "a".repeat(72);
        String hash = encoder.encode(prefix);
        assertThat(encoder.matches(prefix, hash)).isTrue();
        assertThat(encoder.matches(prefix + "X", hash)).isFalse();
        assertThat(encoder.matches(prefix + "Y", hash)).isFalse();
        assertThatThrownBy(() -> encoder.encode(prefix + "X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void enforcesBytesRatherThanCharacterCount() {
        String boundary = "餐".repeat(24);
        assertThat(encoder.matches(boundary, encoder.encode(boundary))).isTrue();
        assertThatThrownBy(() -> encoder.encode(boundary + "餐")).isInstanceOf(IllegalArgumentException.class);
        assertThat(encoder.matches(null, encoder.encode("normal-password"))).isFalse();
    }

    @Test void validatesRegistrationLoginAndAccountConfirmationBeforeExecution() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var registration = new AuthDtos.RegisterRequest();
            registration.setUsername("alice"); registration.setCaptchaId("id"); registration.setCaptchaAnswer("4");
            registration.setPassword("餐".repeat(24));
            assertThat(validator.validate(registration)).isEmpty();
            registration.setPassword("餐".repeat(25));
            assertThat(validator.validate(registration)).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
            var login = new AuthDtos.LoginRequest();
            login.setUsername("alice"); login.setCaptchaId("id"); login.setCaptchaAnswer("4"); login.setPassword("a".repeat(73));
            assertThat(validator.validate(login)).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
            var deletion = new DeleteAccountRequest(); deletion.setPassword("餐".repeat(25));
            assertThat(validator.validate(deletion)).isNotEmpty();
        }
    }
}
