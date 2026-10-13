package com.example.mealcheck.service;

import com.example.mealcheck.dto.MealAnalysisRequestResponse;
import com.example.mealcheck.entity.UserAccount;
import com.example.mealcheck.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class MealAnalysisRequestServiceTest {
    @Test void keyedSynchronousDeadlineSurvivesPreparationAndPreventsLateCompletion() {
        var analysis=mock(MealAnalysisService.class);
        var keyed=new MealAnalysisRequestService(store,analysis,validation,limits);
        when(store.claim(user,key,"hash","current")).thenReturn(new MealAnalysisRequestStore.Claim("token","balanced",null));
        when(analysis.analysisDeadline(user)).thenAnswer(call->AiCallContext.open(user.getId(),Duration.ofMillis(100)));
        var prepared=mock(MealAnalysisService.PreparedMeal.class);
        when(analysis.prepare(user,image,"balanced")).thenReturn(prepared);
        when(store.complete(user,key,"token",prepared)).thenAnswer(call->{
            assertThat(AiCallContext.capture().userId()).isEqualTo(user.getId());
            Thread.sleep(150);AiCallContext.checkExecution();return null;
        });
        assertThatThrownBy(()->keyed.submit(user,key,image,"current")).isInstanceOf(RemoteCallGuard.RemoteCallDeadlineException.class);
        verify(store).fail(eq(user.getId()),eq(key),eq("token"),eq("SAVE"),isA(RemoteCallGuard.RemoteCallDeadlineException.class));
        assertThat(AiCallContext.capture()).isNull();
    }
    final MealAnalysisRequestStore store = mock(MealAnalysisRequestStore.class);
    final ImageUploadValidationService validation = mock(ImageUploadValidationService.class);
    final RateLimitService limits = mock(RateLimitService.class);
    final MealAnalysisRequestService service = new MealAnalysisRequestService(store, mock(MealAnalysisService.class), validation, limits);
    final MockMultipartFile image = new MockMultipartFile("image", new byte[]{1});
    final String key = "meal-request-key-0001";
    final UserPrincipal user;
    MealAnalysisRequestServiceTest() {
        var account = new UserAccount(); account.setId(1L); account.setUsername("alice"); user = new UserPrincipal(account);
        when(limits.consumeUploadIngress("alice")).thenReturn(new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.ALLOWED,20,19,Duration.ZERO));
        when(validation.payloadHash(image,"current")).thenReturn("hash");
    }
    @Test void duplicateRequestReturnsWithoutDecoding() {
        var existing = new MealAnalysisRequestResponse(key,"SUCCEEDED",null,0);
        when(store.claimUpload(user,key,"hash","current")).thenReturn(new MealAnalysisRequestStore.Claim(null,"balanced",existing));
        assertThat(service.enqueue(user,key,image,"current")).isSameAs(existing);
        verify(validation,never()).validateAndNormalize(any());
    }
    @Test void quotaRejectionAndConflictingPayloadDoNotDecode() {
        when(store.claimUpload(user,key,"hash","current")).thenThrow(new RateLimitExceededException("quota",3,0,Duration.ofSeconds(6)));
        assertThatThrownBy(() -> service.enqueue(user,key,image,"current")).isInstanceOf(RateLimitExceededException.class);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(store).claimUpload(user,key,"hash","current");
        assertThatThrownBy(() -> service.enqueue(user,key,image,"current")).isInstanceOf(ResponseStatusException.class);
        verify(validation,never()).validateAndNormalize(any());
    }
    @Test void ingressRejectionDoesNotReadOrDecodeTheUpload() {
        when(limits.consumeUploadIngress("alice")).thenReturn(new RateLimitService.RateLimitDecision(RateLimitService.RateLimitStatus.LIMIT_EXCEEDED,20,0,Duration.ofSeconds(1)));
        assertThatThrownBy(() -> service.enqueue(user,key,image,"current")).isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(store);
        verify(validation,never()).payloadHash(any(),any());
        verify(validation,never()).validateAndNormalize(any());
    }
    @Test void invalidImageReleasesTheClaimForRetry() {
        when(store.claimUpload(user,key,"hash","current")).thenReturn(new MealAnalysisRequestStore.Claim("token","balanced",null));
        var error = new IllegalArgumentException("invalid image");
        when(validation.validateAndNormalize(image)).thenThrow(error);
        assertThatThrownBy(() -> service.enqueue(user,key,image,"current")).isSameAs(error);
        verify(store).fail(1L,key,"token","VALIDATE",error);
    }
}
