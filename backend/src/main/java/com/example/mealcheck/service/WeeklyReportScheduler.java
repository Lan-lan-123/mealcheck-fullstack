package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import com.example.mealcheck.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

@Service
public class WeeklyReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
    private final UserAccountRepository userAccountRepository;
    private final WeeklyReportJobService jobService;
    private final AppProperties properties;

    public WeeklyReportScheduler(UserAccountRepository userAccountRepository,
                                 WeeklyReportJobService jobService,
                                 AppProperties properties) {
        this.userAccountRepository = userAccountRepository;
        this.jobService = jobService;
        this.properties = properties;
    }

    @Scheduled(cron = "${mealcheck.weekly-report.cron:0 0 6 * * MON}")
    public void generateWeeklySnapshots() {
        int batchSize = Math.max(10, Math.min(properties.getWeeklyReport().getBatchSize(), 500));
        int pageNumber = 0;
        int enqueued = 0;
        LocalDate reportWeek = LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Page<com.example.mealcheck.entity.UserAccount> page;
        do {
            page = userAccountRepository.findAll(
                    PageRequest.of(pageNumber++, batchSize, Sort.by(Sort.Direction.ASC, "id"))
            );
            if (!page.isEmpty()) {
                enqueued += jobService.enqueue(
                        page.getContent().stream().map(user -> user.getId()).toList(),
                        7,
                        reportWeek
                );
            }
        } while (page.hasNext());
        log.info("Weekly report scheduling completed. reportWeek={}, jobsEnqueued={}", reportWeek, enqueued);
    }
}
