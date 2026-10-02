package com.example.shortlink.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class VisitCleanupConfiguration {
    @Bean
    ThreadPoolTaskScheduler visitCleanupScheduler(Clock clock) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("visit-cleanup-");
        scheduler.setClock(clock);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
