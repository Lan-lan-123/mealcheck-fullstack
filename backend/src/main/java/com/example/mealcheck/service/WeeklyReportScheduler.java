package com.example.mealcheck.service;

import com.example.mealcheck.util.AppTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

@Service
public class WeeklyReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
    private final WeeklyReportDispatchService dispatch;
    private final BackgroundTaskDispatcher background;

    public WeeklyReportScheduler(WeeklyReportDispatchService dispatch, BackgroundTaskDispatcher background) {
        this.dispatch = dispatch;
        this.background = background;
    }

    @Scheduled(cron = "${mealcheck.weekly-report.cron:0 0 6 * * MON}", zone = "${MEALCHECK_BUSINESS_ZONE:Asia/Shanghai}")
    public void tick() {
        background.submit("weekly-enqueue", this::generateWeeklySnapshots);
    }

    public void generateWeeklySnapshots() {
        LocalDate reportWeek = AppTime.today()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        dispatch.start(reportWeek);
        int enqueued=dispatch.dispatchBatch();
        log.info("Weekly report scheduling completed. reportWeek={}, jobsEnqueued={}", reportWeek, enqueued);
    }
    @Scheduled(fixedDelayString="${mealcheck.weekly-report.enqueue-resume-ms:60000}")
    public void resume() { background.submit("weekly-enqueue", dispatch::dispatchBatch); }
}
