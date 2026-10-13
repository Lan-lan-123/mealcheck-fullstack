package com.example.mealcheck.controller;

import com.example.mealcheck.dto.WeeklyReportJobResponse;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.WeeklyReportRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/reports/weekly/jobs")
public class WeeklyReportJobController {
    private final WeeklyReportRequestService service;
    public WeeklyReportJobController(WeeklyReportRequestService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<WeeklyReportJobResponse> request(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "7") int days) {
        var job = service.request(user, days);
        int status = "SUCCEEDED".equals(job.status()) || "DEAD".equals(job.status()) ? 200 : 202;
        return ResponseEntity.status(status).location(URI.create("/api/reports/weekly/jobs/" + job.id()))
                .body(job);
    }

    @GetMapping("/{id}")
    public WeeklyReportJobResponse status(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long id) {
        return service.status(user, id);
    }
}
