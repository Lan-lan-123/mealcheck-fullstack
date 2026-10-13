package com.example.mealcheck.service;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WeeklyReportSchedulerTest {
    @Test void startsDurableDispatchAndResumesOneBoundedBatch() {
        var dispatch=mock(WeeklyReportDispatchService.class);
        var scheduler=new WeeklyReportScheduler(dispatch,new BackgroundTaskDispatcher(Runnable::run));
        scheduler.generateWeeklySnapshots();scheduler.resume();
        verify(dispatch).start(any());verify(dispatch,times(2)).dispatchBatch();
    }
}
