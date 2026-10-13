package com.example.mealcheck.repository;

import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.AssistantConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface AssistantConversationMessageRepository extends JpaRepository<AssistantConversationMessage, Long> {
    List<AssistantConversationMessage> findByConversationOrderByCreatedAtDesc(
            AssistantConversation conversation, Pageable pageable);
    long countByConversation(AssistantConversation conversation);

    @Query("select m from AssistantConversationMessage m where m.conversation = :conversation order by m.createdAt desc, m.id desc")
    List<AssistantConversationMessage> latestPage(AssistantConversation conversation, Pageable pageable);

    @Query("""
            select m from AssistantConversationMessage m where m.conversation = :conversation
              and (m.createdAt < :beforeTime or (m.createdAt = :beforeTime and m.id < :beforeId))
            order by m.createdAt desc, m.id desc
            """)
    List<AssistantConversationMessage> beforePage(AssistantConversation conversation, LocalDateTime beforeTime,
                                                 long beforeId, Pageable pageable);

    @Query(value = """
            SELECT *
            FROM assistant_conversation_messages
            WHERE conversation_id = :conversationId
            ORDER BY created_at ASC, id ASC
            OFFSET :messageOffset
            LIMIT :messageLimit
            """, nativeQuery = true)
    List<AssistantConversationMessage> findWindow(long conversationId, int messageOffset, int messageLimit);
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
