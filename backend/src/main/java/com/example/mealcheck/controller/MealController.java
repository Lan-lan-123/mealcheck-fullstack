package com.example.mealcheck.controller;

import com.example.mealcheck.dto.MealAnalysisResponse;
import com.example.mealcheck.dto.MealRecordResponse;
import com.example.mealcheck.dto.WeeklyReportResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.MealAnalysisService;
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
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api")
public class MealController {

    private final MealAnalysisService mealAnalysisService;
    private final WeeklyReportService weeklyReportService;

    public MealController(MealAnalysisService mealAnalysisService,
                          WeeklyReportService weeklyReportService) {
        this.mealAnalysisService = mealAnalysisService;
        this.weeklyReportService = weeklyReportService;
    }

    @PostMapping("/meals/analyze")
    public MealAnalysisResponse analyze(@AuthenticationPrincipal UserPrincipal principal,
                                        @RequestParam("image") MultipartFile image,
                                        @RequestParam(value = "goal", defaultValue = "balanced") String goal) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请上传饭菜图片");
        }

        return mealAnalysisService.analyze(principal, image, goal);
    }

    @GetMapping("/meals")
    public List<MealRecordResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return mealAnalysisService.listRecent(principal);
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