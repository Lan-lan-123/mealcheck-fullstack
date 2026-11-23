package com.example.mealcheck.service;

import com.example.mealcheck.dto.RagChainReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RagChainAssessmentTest {
    final ObjectMapper json = new ObjectMapper();
    RagChainReport.Retrieval search(String status, String... titles) {
        return new RagChainReport.Retrieval("call", "实际查询", 5, status, 10,
                java.util.Arrays.stream(titles).map(title -> new RagChainReport.Hit(1L, title, "general", .9)).toList(), null);
    }
    @Test void successfulSupplementRepairsFinalCoverageWithoutReplacingPrimaryMetrics() throws Exception {
        var report = RagChainAssessment.assess(json.readTree("{\"expectedTitleKeywords\":[\"目标\"]}"),
                List.of(search("SUCCESS", "无关"), search("SUCCESS", "目标知识")));
        assertThat(report.retrievalStatus()).isEqualTo("PASSED");
        assertThat(report.hitAt5()).isFalse();
        assertThat(report.finalEvidenceCovered()).isTrue();
        assertThat(report.recoveredBySupplement()).isTrue();
        assertThat(report.retrievals()).hasSize(2);
    }
    @Test void metricsReflectTheActualFirstResultRanking() throws Exception {
        var report = RagChainAssessment.assess(json.readTree("{\"expectedTitleKeywords\":[\"目标\"]}"),
                List.of(search("SUCCESS", "其他", "其他2", "其他3", "目标知识")));
        assertThat(report.retrievalPassed()).isTrue();
        assertThat(report.firstRelevantRank()).isEqualTo(4);
        assertThat(report.hitAt3()).isFalse(); assertThat(report.hitAt5()).isTrue();
        assertThat(report.reciprocalRank()).isEqualTo(.25);
        assertThat(report.ndcgAt5()).isCloseTo(1.0/(Math.log(5)/Math.log(2)), within(.00001));
    }
    @Test void missingSearchIsNotAnEmptySuccessfulRetrieval() throws Exception {
        var expected = json.readTree("{\"expectedTitleKeywords\":[\"目标\"]}");
        assertThat(RagChainAssessment.assess(expected, List.of()).retrievalStatus()).isEqualTo("NOT_CALLED");
        assertThat(RagChainAssessment.assess(expected, List.of(search("SUCCESS"))).retrievalStatus()).isEqualTo("FAILED");
        assertThat(RagChainAssessment.assess(expected, List.of(search("ERROR"))).retrievalStatus()).isEqualTo("ERROR");
    }
    @Test void noAnswerMustSkipSearchAndSearchErrorsNeverPass() throws Exception {
        var expected = json.readTree("{\"expectNoAnswer\":true}");
        assertThat(RagChainAssessment.assess(expected, List.of()).retrievalPassed()).isTrue();
        assertThat(RagChainAssessment.assess(expected, List.of(search("SUCCESS"))).retrievalPassed()).isFalse();
        assertThat(RagChainAssessment.assess(expected, List.of(search("ERROR"))).retrievalStatus()).isEqualTo("ERROR");
    }
}
