package com.example.shortlink.stats;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import java.time.*;
import java.util.concurrent.ScheduledFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisitCleanupScheduleTest {
    @Test
    void startupShanghaiDailyAndConditionalFifteenMinuteCatchUpStopWithApplication() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T15:59:59Z"), ZoneOffset.UTC);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        VisitLogCleanup cleanup = mock(VisitLogCleanup.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        doReturn(future).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        VisitCleanupSchedule schedule = new VisitCleanupSchedule(scheduler, cleanup, clock);
        schedule.start();
        schedule.start();
        ArgumentCaptor<Runnable> startup = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(startup.capture(), eq(clock.instant()));
        startup.getValue().run();
        verify(cleanup).runRound();
        ArgumentCaptor<Trigger> daily = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).schedule(any(Runnable.class), daily.capture());
        assertThat(daily.getValue().nextExecution(new SimpleTriggerContext(clock)))
                .isEqualTo(Instant.parse("2026-10-01T16:10:00Z"));
        ArgumentCaptor<Runnable> catchup = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(catchup.capture(), eq(clock.instant().plusSeconds(900)), eq(Duration.ofMinutes(15)));
        catchup.getValue().run();
        verify(cleanup).runRound();
        when(cleanup.needsCatchUp()).thenReturn(true);
        catchup.getValue().run();
        verify(cleanup, times(2)).runRound();
        schedule.stop();
        verify(future, times(3)).cancel(false);
    }
}
