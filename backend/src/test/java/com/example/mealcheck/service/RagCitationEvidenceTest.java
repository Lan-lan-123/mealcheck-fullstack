package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RagCitationEvidenceTest {
    @Test void acceptsPresentationWhitespaceAndTruncatedExcerptsButRejectsDifferentSourceText() {
        assertThat(RagCitationEvidence.textMatches("建议\n\n搭配蔬菜。","建议 搭配蔬菜。")).isTrue();
        assertThat(RagCitationEvidence.textMatches("建议搭配蔬菜和主食。","建议搭配蔬菜...")).isTrue();
        assertThat(RagCitationEvidence.textMatches("建议搭配蔬菜。","建议大量饮酒...")).isFalse();
        assertThat(RagCitationEvidence.textMatches("建议搭配蔬菜。","...")).isFalse();
        assertThat(RagCitationEvidence.textMatches("建议搭配蔬菜。","")).isFalse();
    }
}
