package com.example.mealcheck.service;

import com.example.mealcheck.dto.MealAnalysisRequestResponse;
import com.example.mealcheck.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MealAnalysisRequestService {
    private final MealAnalysisRequestStore store;
    private final MealAnalysisService analysis;
    private final ImageUploadValidationService validation;
    private final RateLimitService limits;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ImageStorageService images;
    public MealAnalysisRequestService(MealAnalysisRequestStore store, MealAnalysisService analysis,
            ImageUploadValidationService validation, RateLimitService limits) {
        this.store = store; this.analysis = analysis; this.validation = validation; this.limits = limits;
    }
    public MealAnalysisRequestResponse submit(UserPrincipal principal, String key, MultipartFile image, String goal) {
        validateKey(key);
        enforceIngress(principal);
        String hash = validation.payloadHash(image, goal);
        var claim = store.claim(principal, key, hash, goal);
        if (claim.existing() != null) return claim.existing();
        String phase = "ANALYZE";
        try (var deadline = analysis.analysisDeadline(principal)) {
            var prepared = analysis.prepare(principal, image, claim.goal());
            phase = "SAVE";
            AiCallContext.checkExecution();
            return store.complete(principal, key, claim.token(), prepared);
        }
        catch (RuntimeException error) { store.fail(principal.getId(), key, claim.token(), phase, error); throw error; }
    }
    public MealAnalysisRequestResponse status(UserPrincipal user, String key) { validateKey(key); return store.status(user, key); }
    public MealAnalysisRequestResponse enqueue(UserPrincipal principal,String key,MultipartFile image,String goal) {
        validateKey(key);
        enforceIngress(principal);
        String hash = validation.payloadHash(image, goal);
        var claim = store.claimUpload(principal, key, hash, goal);
        if (claim.existing() != null) return claim.existing();
        String reference=null;
        try {
            var validated = validation.validateAndNormalize(image);
            if(images != null && images.usesObjectStorage()) {
                reference=images.store(principal.getId(),validated);
                var result=store.enqueueClaimedReference(principal,key,claim.token(),validated,reference);
                if(!"PENDING".equals(result.status()))images.deleteSafely(reference);
                return result;
            }
            return store.enqueueClaimed(principal, key, claim.token(), validated);
        } catch (RuntimeException error) {
            // A commit failure can be ambiguous. Transaction rollback hooks or the
            // reference-aware orphan scan reclaim storage; never delete a possibly accepted payload here.
            store.fail(principal.getId(), key, claim.token(), "VALIDATE", error);
            throw error;
        }
    }
    private void enforceIngress(UserPrincipal principal) {
        var limit = limits.consumeUploadIngress(principal.getUsername());
        if (limit.unavailable()) throw new IllegalStateException("上传限流服务暂时不可用，请稍后重试。");
        if (!limit.allowed()) throw new RateLimitExceededException("上传请求太频繁，请稍后重试。",
                limit.limit(), limit.remaining(), limit.retryAfter());
    }
    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,80}")) throw new IllegalArgumentException("请求标识必须为 16 到 80 位字母、数字、下划线或短横线");
    }
}
