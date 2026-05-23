package com.example.mealcheck.repository;

import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.AssistantConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface AssistantConversationMessageRepository extends JpaRepository<AssistantConversationMessage, Long> {
    List<AssistantConversationMessage> findByConversationOrderByCreatedAtAsc(AssistantConversation conversation);
    void deleteByConversation(AssistantConversation conversation);
    long countByRole(String role);
    List<AssistantConversationMessage> findByRoleAndCreatedAtAfterOrderByCreatedAtAsc(String role, LocalDateTime createdAt);
    List<AssistantConversationMessage> findTop500ByRoleOrderByCreatedAtDesc(String role);

    @Query("""
            select m.conversation.user.username, count(m)
            from AssistantConversationMessage m
            where m.role = 'user'
            group by m.conversation.user.username
            order by count(m) desc
            """)
    List<Object[]> countUserQuestionsByUsername();
}
