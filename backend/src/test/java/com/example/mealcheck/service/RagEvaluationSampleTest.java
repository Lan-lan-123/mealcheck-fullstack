package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RagEvaluationSampleTest {
    @Test void excludesPreviouslyRunCasesBeforeDrawingWithoutChangingTheSeed() {
        var population=java.util.stream.IntStream.rangeClosed(1,200).boxed().toList();
        var filtered=RagEvaluationSample.excluding(population,"13,38,64,74,94,113,129,130,134,158",200);
        var selected=RagEvaluationSample.selectFrom(filtered,10,20261019190500L);
        assertThat(filtered).hasSize(190);
        assertThat(selected).hasSize(10).doesNotContain(13,38,64,74,94,113,129,130,134,158);
        assertThat(RagEvaluationSample.selectFrom(filtered,10,20261019190500L)).isEqualTo(selected);
        assertThatThrownBy(()->RagEvaluationSample.excluding(population,"201",200)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void deterministicSamplingDoesNotRepeatOrExceedRequestedCount() {
        // Preserve deterministic sampling for both historical and current dataset sizes.
        for (int total : java.util.List.of(158,200)) {
            var ids=RagEvaluationSample.select(total,10,20261018L);
            assertThat(ids).hasSize(10).doesNotHaveDuplicates().allMatch(id->id>=1 && id<=total);
            assertThat(RagEvaluationSample.select(total,10,20261018L)).isEqualTo(ids);
            assertThatThrownBy(()->RagEvaluationSample.select(total,total+1,1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(RagEvaluationSample.select(200,200,20261018L))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,200).boxed().toList());
    }
    @Test void subsetBenchmarkQueriesOnlySelectedQuestions() {
        var properties=new AppProperties();
        var vectors=mock(PgVectorKnowledgeService.class);
        when(vectors.search(anyString(),anyInt())).thenReturn(java.util.List.of());
        var service=new RagBenchmarkService(vectors,new ObjectMapper(),mock(KnowledgeRerankerService.class),new KnowledgeResultPostProcessor(properties),properties);
        String question="减脂时晚餐怎么吃更合适？";
        assertThat(service.evaluateQuestions(Set.of(question)).getCaseCount()).isEqualTo(1);
        verify(vectors,times(1)).search(eq(question),anyInt());
        verifyNoMoreInteractions(vectors);
    }
}
