package com.example.mealcheck.dto.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRequestTest {

    @Test
    void questionTextPrefersQuestionField() {
        AssistantRequest request = new AssistantRequest();
        request.setQuestion("  question text  ");
        request.setMessage("message text");

        assertThat(request.questionText()).isEqualTo("question text");
    }

    @Test
    void questionTextFallsBackToLegacyMessageField() {
        AssistantRequest request = new AssistantRequest();
        request.setMessage("  legacy message  ");

        assertThat(request.questionText()).isEqualTo("legacy message");
    }

    @Test
    void questionTextReturnsEmptyStringWhenNoTextExists() {
        AssistantRequest request = new AssistantRequest();

        assertThat(request.questionText()).isEmpty();
    }
}
