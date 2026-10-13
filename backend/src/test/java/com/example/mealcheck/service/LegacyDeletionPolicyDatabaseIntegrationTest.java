package com.example.mealcheck.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class LegacyDeletionPolicyDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test void upgradeRemovesOnlyLegacyDuplicatesAndPreservesDeletionPolicyAndRows() {
        Flyway.configure().configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false")).dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("5").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("ALTER TABLE meal_records ADD CONSTRAINT legacy_meal_user FOREIGN KEY(user_id) REFERENCES users(id)");
        jdbc.execute("ALTER TABLE admin_audit_logs ADD CONSTRAINT legacy_audit_admin FOREIGN KEY(admin_id) REFERENCES users(id)");
        jdbc.execute("ALTER TABLE assistant_conversation_messages ADD CONSTRAINT legacy_message_conversation FOREIGN KEY(conversation_id) REFERENCES assistant_conversations(id)");
        // Legacy Hibernate constraints existed BEFORE V6. Constraint trigger order
        // matters: adding duplicates after CASCADE does not reproduce the old DB.
        Flyway.configure().configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false")).dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("26").load().migrate();
        // An unrelated restrictive FK must not be removed.
        jdbc.execute("CREATE TABLE unrelated_reference(user_id BIGINT REFERENCES users(id))");
        jdbc.execute("INSERT INTO users(id,username,password_hash) VALUES(1,'legacy','hash')");
        jdbc.execute("INSERT INTO meal_records(id,user_id,score) VALUES(1,1,80)");
        jdbc.execute("INSERT INTO meal_nutrition_snapshots(record_id,snapshot_json) VALUES(1,'{}')");
        jdbc.execute("INSERT INTO admin_audit_logs(admin_id,action) VALUES(1,'TEST')");
        jdbc.execute("INSERT INTO assistant_conversations(id,user_id,title) VALUES(1,1,'Test')");
        jdbc.execute("INSERT INTO assistant_conversation_messages(conversation_id,role,text) VALUES(1,'user','test')");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id=1")).isInstanceOf(DataIntegrityViolationException.class);
        Flyway.configure().configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false")).dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_records", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname LIKE 'legacy_%'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='unrelated_reference'::regclass AND contype='f'", Long.class)).isEqualTo(1);
        jdbc.update("DELETE FROM users WHERE id=1");
        for (String table : new String[]{"meal_records", "meal_nutrition_snapshots", "assistant_conversations", "assistant_conversation_messages"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs WHERE admin_id IS NULL", Long.class)).isEqualTo(1);
    }
}
