package com.example.mealcheck.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;

/** Reject truncation while retaining compatibility with existing BCrypt hashes. */
public class StrictBCryptPasswordEncoder implements PasswordEncoder {
    public static final int MAX_BYTES = 72;
    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

    public static boolean withinLimit(CharSequence password) {
        return password != null && password.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        if (!withinLimit(rawPassword)) throw new IllegalArgumentException("密码不能超过 72 个 UTF-8 字节");
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withinLimit(rawPassword) && delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }
}
