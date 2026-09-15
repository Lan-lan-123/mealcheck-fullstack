package com.example.mealcheck.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class UserDeletionDatabaseIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Test
    void deletingUserCascadesPrivateDataButRetainsOperationalHistoryWithoutForeignKeys() throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            long userId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO users(username, password_hash) VALUES ('delete-me', 'hash') RETURNING id")) {
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    userId = result.getLong(1);
                }
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO meal_records(user_id, score) VALUES (" + userId + ", 80)");
                statement.execute("INSERT INTO user_goals(user_id, goal_type) VALUES (" + userId + ", 'balanced')");
                statement.execute("INSERT INTO weekly_report_snapshots(user_id, days) VALUES (" + userId + ", 7)");
                statement.execute("INSERT INTO user_diet_profiles(user_id) VALUES (" + userId + ")");
                statement.execute("INSERT INTO weekly_report_jobs(user_id, report_week, days, status) "
                        + "VALUES (" + userId + ", CURRENT_DATE, 7, 'PENDING')");
                statement.execute("INSERT INTO user_restrictions(user_id, username, restriction_type, blocked_until) "
                        + "VALUES (" + userId + ", 'delete-me', 'NON_FOOD_UPLOAD', CURRENT_TIMESTAMP + INTERVAL '1 hour')");
                statement.execute("INSERT INTO assistant_conversations(user_id, title) VALUES (" + userId
                        + ", 'conversation')");
                statement.execute("INSERT INTO assistant_conversation_messages(conversation_id, role, text) "
                        + "SELECT id, 'user', 'hello' FROM assistant_conversations WHERE user_id = " + userId);
                statement.execute("INSERT INTO non_food_upload_events(user_id, username, window_count, window_minutes, "
                        + "threshold_reached) VALUES (" + userId + ", 'delete-me', 1, 5, false)");
                statement.execute("INSERT INTO admin_audit_logs(admin_id, admin_username, action) VALUES ("
                        + userId + ", 'delete-me', 'TEST')");
                statement.execute("DELETE FROM users WHERE id = " + userId);
            }

            assertThat(count(connection, "meal_records")).isZero();
            assertThat(count(connection, "user_goals")).isZero();
            assertThat(count(connection, "weekly_report_snapshots")).isZero();
            assertThat(count(connection, "user_diet_profiles")).isZero();
            assertThat(count(connection, "weekly_report_jobs")).isZero();
            assertThat(count(connection, "user_restrictions")).isZero();
            assertThat(count(connection, "assistant_conversations")).isZero();
            assertThat(count(connection, "assistant_conversation_messages")).isZero();
            assertThat(count(connection, "non_food_upload_events")).isEqualTo(1);
            assertThat(count(connection, "admin_audit_logs")).isEqualTo(1);
            assertThat(nullForeignKeys(connection, "non_food_upload_events", "user_id")).isEqualTo(1);
            assertThat(nullForeignKeys(connection, "admin_audit_logs", "admin_id")).isEqualTo(1);
        }
    }

    private long count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private long nullForeignKeys(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NULL")) {
            result.next();
            return result.getLong(1);
        }
    }
}
