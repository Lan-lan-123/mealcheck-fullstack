package com.example.mealcheck.service;

import com.example.mealcheck.dto.assistant.AssistantToolCall;
import com.example.mealcheck.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.baseline-on-migrate=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AssistantConversationService.class)
@Testcontainers
class AssistantToolHistoryDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired AssistantConversationService conversations;

    @Test void migrationSupportsDurableToolHistoryAndPreservesAccountIsolation() {
        long ownerId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES ('tool-owner','hash') RETURNING id", Long.class);
        long otherId = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES ('tool-other','hash') RETURNING id", Long.class);
        var owner = users.findById(ownerId).orElseThrow();
        var other = users.findById(otherId).orElseThrow();
        var conversation = conversations.resolve(owner, null, "推荐晚餐搭配");
        // Older messages without tool evidence remain readable after V46.
        conversations.appendTurn(conversation, "你好", "你好", "old-request", "COMPLETE");
        var calls = List.of(new AssistantToolCall("recommend_next_meal", "{\"budget\":25}", "SUCCESS", 1, 11, "MODEL", "找到 3 套搭配"));
        conversations.appendTurn(conversation, "推荐晚餐", "已查询搭配", "new-request", "COMPLETE", calls);
        var history = conversations.detail(owner, conversation.getId());
        assertThat(history.getMessages()).hasSize(4);
        assertThat(history.getMessages().get(1).getToolCalls()).isEmpty();
        assertThat(history.getMessages().get(3).getToolCalls()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT tool_calls_json FROM assistant_conversation_messages WHERE request_id='new-request' AND role='assistant'", String.class))
                .contains("recommend_next_meal", "MODEL");
        var partial=new com.example.mealcheck.dto.assistant.AssistantResponse("只确认一部分",List.of());partial.partial(List.of("UNSUPPORTED_STRENGTH"));
        conversations.appendTurn(conversation,"部分问题",partial.getAnswer(),"partial-request","COMPLETE",List.of(),List.of(),partial);
        assertThat(conversations.detail(owner,conversation.getId()).getMessages().get(5).getAnswerStatus()).isEqualTo("PARTIAL");
        assertThat(conversations.detail(owner,conversation.getId()).getMessages().get(5).getAnswerReasonCodes()).containsExactly("UNSUPPORTED_STRENGTH");
        assertThat(jdbc.queryForObject("SELECT outcome_json FROM assistant_conversation_messages WHERE request_id='partial-request' AND role='assistant'",String.class)).contains("PARTIAL","UNSUPPORTED_STRENGTH");
        assertThatThrownBy(() -> conversations.detail(other, conversation.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
