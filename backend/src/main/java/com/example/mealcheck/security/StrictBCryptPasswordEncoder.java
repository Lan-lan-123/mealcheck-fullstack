package com.example.mealcheck.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;

/** Reject truncation while retaining compatibility with existing BCrypt hashes. */
public class StrictBCryptPasswordEncoder extends BCryptPasswordEncoder {
    public static final int MAX_BYTES = 72;

    public static boolean withinLimit(CharSequence password) {
        return password != null && password.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        if (!withinLimit(rawPassword)) throw new IllegalArgumentException("密码不能超过 72 个 UTF-8 字节");
        return super.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return withinLimit(rawPassword) && super.matches(rawPassword, encodedPassword);
    }
}
