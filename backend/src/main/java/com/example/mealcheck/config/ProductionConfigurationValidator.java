package com.example.mealcheck.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
@Profile("prod")
public class ProductionConfigurationValidator {
    private static final String DEVELOPMENT_JWT_SECRET = "mealcheck-jwt-secret-change-me-please-32chars";
    private final AppProperties properties;
    private final Environment environment;

    public ProductionConfigurationValidator(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        String jwtSecret = properties.getJwt().getSecret();
        require(jwtSecret != null
                        && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 48
                        && !DEVELOPMENT_JWT_SECRET.equals(jwtSecret),
                "MEALCHECK_JWT_SECRET must be a unique production secret of at least 48 bytes");

        String databasePassword = environment.getProperty("spring.datasource.password");
        require(databasePassword != null && !databasePassword.isBlank() && !"mealcheck123".equals(databasePassword),
                "SPRING_DATASOURCE_PASSWORD must not be blank or use the development default");

        String redisPassword = environment.getProperty("spring.data.redis.password");
        require(redisPassword != null && !redisPassword.isBlank(),
                "SPRING_REDIS_PASSWORD is required in production");

        String origins = properties.getCors().getAllowedOrigins();
        if ("rabbitmq".equals(environment.getProperty("mealcheck.analysis.dispatch", "rabbitmq"))) {
            String rabbitPassword = environment.getProperty("spring.rabbitmq.password");
            require(rabbitPassword != null && !rabbitPassword.isBlank()
                            && !"guest".equals(rabbitPassword) && !"mealcheck-rabbit-dev".equals(rabbitPassword),
                    "SPRING_RABBITMQ_PASSWORD must not be blank or use development defaults");
        }
        require(origins != null && !origins.isBlank(), "MEALCHECK_CORS_ALLOWED_ORIGINS is required in production");
        boolean safeOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .allMatch(origin -> origin.startsWith("https://")
                        && !origin.contains("localhost")
                        && !origin.contains("127.0.0.1")
                        && !origin.contains("*"));
        require(safeOrigins, "Production CORS origins must be explicit HTTPS origins");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid production configuration: " + message);
        }
    }
}
