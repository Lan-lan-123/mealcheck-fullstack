package com.example.mealcheck.entity;

import com.example.mealcheck.util.AppTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "assistant_conversation_messages")
public class AssistantConversationMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    private AssistantConversation conversation;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false)
    private LocalDateTime createdAt = AppTime.now();

    @Column(length=64)
    private String requestId;
    @Column(nullable=false,length=16)
    private String deliveryStatus="COMPLETE";
    @Column(columnDefinition = "TEXT")
    private String toolCallsJson;
    @Column(columnDefinition = "TEXT")
    private String referencesJson;
    @Column(columnDefinition = "TEXT")
    private String outcomeJson;
    public String getOutcomeJson() {return outcomeJson;}
    public void setOutcomeJson(String value) {outcomeJson=value;}
    public String getReferencesJson() { return referencesJson; }
    public void setReferencesJson(String value) { referencesJson = value; }
    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String value) { toolCallsJson = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId=v; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String v) { deliveryStatus=v; }

    public Long getId() { return id; }
    public AssistantConversation getConversation() { return conversation; }
    public void setConversation(AssistantConversation conversation) { this.conversation = conversation; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
