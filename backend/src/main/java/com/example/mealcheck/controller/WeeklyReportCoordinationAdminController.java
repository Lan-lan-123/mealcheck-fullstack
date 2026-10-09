package com.example.mealcheck.controller;

import com.example.mealcheck.dto.WeeklyReportCoordinationResponse;
import com.example.mealcheck.dto.WeeklyReportCoordinationPageResponse;
import com.example.mealcheck.service.WeeklyReportCoordinationQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/weekly-report-runs")
@PreAuthorize("hasRole('ADMIN')")
public class WeeklyReportCoordinationAdminController {
    private final WeeklyReportCoordinationQueryService queryService;

    public WeeklyReportCoordinationAdminController(WeeklyReportCoordinationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public WeeklyReportCoordinationPageResponse recent(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "jobId", required = false) Long jobId) {
        return queryService.recent(page, size, status, userId, jobId);
    }

    @GetMapping("/{runId}")
    public WeeklyReportCoordinationResponse detail(@PathVariable String runId) {
        return queryService.detail(runId);
    }
}
