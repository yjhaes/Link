package com.example.shortlink.stats;


import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

@Component
public class VisitCleanupSchedule {
    private final TaskScheduler scheduler;
    private final VisitLogCleanup cleanup;
    private final Clock clock;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    public VisitCleanupSchedule(
            @Qualifier("visitCleanupScheduler") TaskScheduler scheduler,
            VisitLogCleanup cleanup,
            Clock clock) {
        this.scheduler = scheduler;
        this.cleanup = cleanup;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!tasks.isEmpty()) return;
        tasks.add(scheduler.schedule(cleanup::runRound, clock.instant()));
        tasks.add(
                scheduler.schedule(
                        cleanup::runRound, new CronTrigger("0 10 0 * * *", StatsDateRange.ZONE)));
        tasks.add(
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            if (cleanup.needsCatchUp()) cleanup.runRound();
                        },
                        clock.instant().plus(Duration.ofMinutes(15)),
                        Duration.ofMinutes(15)));
    }

    @PreDestroy
    public synchronized void stop() {
        tasks.forEach(
                task -> {
                    if (task != null) task.cancel(false);
                });
        tasks.clear();
    }
}
