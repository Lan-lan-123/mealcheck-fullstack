package com.example.mealcheck.service;

import com.example.mealcheck.repository.UserAccountRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WeeklyReportScheduler {
    private final UserAccountRepository userAccountRepository;
    private final WeeklyReportService weeklyReportService;

    public WeeklyReportScheduler(UserAccountRepository userAccountRepository,
                                 WeeklyReportService weeklyReportService) {
        this.userAccountRepository = userAccountRepository;
        this.weeklyReportService = weeklyReportService;
    }

    @Scheduled(cron = "${mealcheck.weekly-report.cron:0 0 6 * * MON}")
    public void generateWeeklySnapshots() {
        userAccountRepository.findAll().forEach(user -> weeklyReportService.generateAndStore(user, 7));
    }
}
