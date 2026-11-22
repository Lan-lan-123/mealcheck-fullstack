package com.example.mealcheck.controller;

/** Existing authorization/validation suites submit valid CSRF tokens; lifecycle tests exercise real failures separately. */
@org.springframework.boot.test.context.TestConfiguration
public class CsrfTestRequests {
    @org.springframework.context.annotation.Bean
    org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer validCsrfRequests() {
        return builder->builder.defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()));
    }
}
