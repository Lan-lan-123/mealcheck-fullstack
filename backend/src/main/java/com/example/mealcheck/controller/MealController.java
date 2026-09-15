package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealAnalysisResponse;
import com.example.mealcheck.dto.MealRecordPageResponse;
import com.example.mealcheck.dto.MealRecordResponse;
import com.example.mealcheck.dto.UserMealTrendResponse;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealAnalysisService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;

import java.util.List;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api")
public class MealController {

    private final MealAnalysisService mealAnalysisService;
    private final WeeklyReportService weeklyReportService;
    private final RateLimitService rateLimitService;

    public MealController(MealAnalysisService mealAnalysisService,
                          WeeklyReportService weeklyReportService,
                          RateLimitService rateLimitService) {
        this.mealAnalysisService = mealAnalysisService;
        this.weeklyReportService = weeklyReportService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/meals/analyze")
    public ResponseEntity<MealAnalysisResponse> analyze(@AuthenticationPrincipal UserPrincipal principal,
                                                        @RequestParam("image") MultipartFile image,
                                                        @RequestParam(value = "goal", defaultValue = "current") String goal) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请上传饭菜图片");
        }

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
        Path imagePath = mealAnalysisService.getMealImagePath(principal, id);
        Resource resource = new UrlResource(imagePath.toUri());

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(imagePath);
        if (contentType == null) {
            contentType = "image/jpeg";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

}
