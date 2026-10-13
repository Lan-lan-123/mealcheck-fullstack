package com.example.mealcheck.controller;

import com.example.mealcheck.dto.TrackingDtos.*;
import com.example.mealcheck.security.UserPrincipal;
import com.example.mealcheck.service.WeeklyReportHistoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/weekly/history")
public class WeeklyReportHistoryController {
    private final WeeklyReportHistoryService history;
    public WeeklyReportHistoryController(WeeklyReportHistoryService history) { this.history=history; }
    @GetMapping public Page<ReportHistory> page(@AuthenticationPrincipal UserPrincipal user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return history.page(user.getId(),page,size); }
    @GetMapping("/{id}") public ArchivedReport detail(@AuthenticationPrincipal UserPrincipal user,@PathVariable long id) { return history.owned(user.getId(),id); }
}
