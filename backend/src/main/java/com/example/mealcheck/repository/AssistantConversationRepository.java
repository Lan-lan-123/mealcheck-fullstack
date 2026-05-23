package com.example.mealcheck.repository;

import com.example.mealcheck.entity.AssistantConversation;
import com.example.mealcheck.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {
    List<AssistantConversation> findTop20ByUserOrderByUpdatedAtDesc(UserAccount user);
    Optional<AssistantConversation> findByIdAndUser(Long id, UserAccount user);
}
