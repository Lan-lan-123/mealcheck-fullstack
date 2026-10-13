package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealAnalysisResponse;
import com.example.mealcheck.dto.MealRecordPageResponse;
import com.example.mealcheck.dto.MealRecordResponse;
import com.example.mealcheck.dto.UserMealTrendResponse;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealAnalysisService;
import com.example.mealcheck.service.MealAnalysisRequestService;
import com.example.mealcheck.service.RateLimitExceededException;
import com.example.mealcheck.service.RateLimitService;
import com.example.mealcheck.service.WeeklyReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

import java.util.List;
import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class MealController {

    private final MealAnalysisService mealAnalysisService;
    private final WeeklyReportService weeklyReportService;
    private final RateLimitService rateLimitService;
    private final MealAnalysisRequestService analysisRequests;
    private final com.example.mealcheck.service.MealAnalysisRequestStore requestStore;

    public MealController(MealAnalysisService mealAnalysisService,
                          WeeklyReportService weeklyReportService,
                          RateLimitService rateLimitService, MealAnalysisRequestService analysisRequests,
                          com.example.mealcheck.service.MealAnalysisRequestStore requestStore) {
        this.mealAnalysisService = mealAnalysisService;
        this.weeklyReportService = weeklyReportService;
        this.rateLimitService = rateLimitService;
        this.analysisRequests = analysisRequests;
        this.requestStore=requestStore;
    }

    @PostMapping("/meals/analyze")
    public ResponseEntity<?> analyze(@AuthenticationPrincipal UserPrincipal principal,
                                                        @RequestParam("image") MultipartFile image,
                                                        @RequestParam(value = "goal", defaultValue = "current") String goal,
                                                        @RequestHeader(value="Idempotency-Key", required=false) String key) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请上传饭菜图片");
        }
        if (key != null) {
            var response = analysisRequests.submit(principal, key, image, goal);
            return ("PROCESSING".equals(response.status()) || "PENDING".equals(response.status())) ? ResponseEntity.accepted().header("Retry-After", Integer.toString(response.retryAfterSeconds())).body(response)
                    : ResponseEntity.ok(response.result());
        }

        String legacySlot = requestStore.reserveLegacy(principal);
        try {
            RateLimitService.RateLimitDecision rateLimit = rateLimitService.consumeMealAnalysis(principal.getUsername());
            if (rateLimit.unavailable()) {
                throw new IllegalStateException("限流服务暂时不可用，请稍后重试。");
            }
            if (!rateLimit.allowed()) {
                throw new RateLimitExceededException(
                        "图片分析太频繁了，请稍后再试。",
                        rateLimit.limit(),
                        rateLimit.remaining(),
                        rateLimit.retryAfter()
                );
            }

            MealAnalysisResponse response = mealAnalysisService.analyze(principal, image, goal);
            return ResponseEntity.ok()
                    .headers(RateLimitHttpHeaders.from(rateLimit))
                    .body(response);
        } finally {
            requestStore.releaseLegacy(principal, legacySlot);
        }
    }

    @GetMapping("/meals")
    public Object list(@AuthenticationPrincipal UserPrincipal principal,
                       @RequestParam(value = "page", required = false) Integer page,
                       @RequestParam(value = "size", required = false) Integer size,
                       @RequestParam(value = "goal", required = false) String goal,
                       @RequestParam(value = "from", required = false) LocalDate from,
                       @RequestParam(value = "to", required = false) LocalDate to,
                       @RequestParam(value = "minScore", required = false) Integer minScore,
                       @RequestParam(value = "maxScore", required = false) Integer maxScore) {
        if (page == null && size == null && goal == null && from == null && to == null && minScore == null && maxScore == null) {
            return mealAnalysisService.listRecent(principal);
        }
        return mealAnalysisService.listRecentPage(
                principal,
                page == null ? 0 : page,
                size == null ? 10 : size,
                goal,
                from,
                to,
                minScore,
                maxScore
        );
    }

    @DeleteMapping("/meals/{id}")
    public ResponseEntity<Void> deleteMeal(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id) {
        mealAnalysisService.deleteMeal(principal, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reports/weekly")
    public WeeklyReportResponse weekly(@AuthenticationPrincipal UserPrincipal principal,
                                       @RequestParam(value = "days", defaultValue = "7") int days) {
        return weeklyReportService.generate(principal, days);
    }

    @GetMapping("/reports/weekly/latest")
    public WeeklyReportResponse latestWeekly(@AuthenticationPrincipal UserPrincipal principal) {
        return weeklyReportService.latest(principal);
    }

    @GetMapping("/meals/trends")
    public UserMealTrendResponse trends(@AuthenticationPrincipal UserPrincipal principal,
                                        @RequestParam(value = "days", defaultValue = "30") int days) {
        return mealAnalysisService.trend(principal, days);
    }

    @GetMapping("/meals/{id}/image")
    public ResponseEntity<Resource> getMealImage(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable Long id) throws Exception {
        Resource resource = mealAnalysisService.getMealImageResource(principal, id);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String filename=resource.getFilename();
        String contentType=filename != null && filename.endsWith(".png") ? "image/png"
                : filename != null && filename.endsWith(".webp") ? "image/webp" : "image/jpeg";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

}
