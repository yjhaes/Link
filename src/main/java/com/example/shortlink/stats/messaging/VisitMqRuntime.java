package com.example.shortlink.stats.messaging;

import jakarta.annotation.PreDestroy;

import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns application MQ intent; all potentially blocking network teardown stays in adapters. */
@Component
public final class VisitMqRuntime {
    private final AsyncVisitRecorder recorder;
    private final RabbitAdmin admin;
    private final SimpleMessageListenerContainer listener;
    private final VisitRabbitProperties properties;
    private final Object lifecycleLock = new Object();
    private volatile boolean closing;
    private boolean started;
    private final ExecutorService startup =
            Executors.newSingleThreadExecutor(
                    task -> {
                        var thread = new Thread(task, "visit-mq-startup");
                        thread.setDaemon(true);
                        return thread;
                    });

    public VisitMqRuntime(
            AsyncVisitRecorder recorder,
            @Qualifier("visitRabbitAdmin") RabbitAdmin admin,
            @Qualifier("visitListener") SimpleMessageListenerContainer listener,
            VisitRabbitProperties properties) {
        this.recorder = recorder;
        this.admin = admin;
        this.listener = listener;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        synchronized (lifecycleLock) {
            if (started || closing) return;
            started = true;
            LoggerFactory.getLogger(VisitMqRuntime.class).info("Application lifecycle: operation=lifecycle result=started");
            recorder.start();
            startup.execute(this::startupLoop);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void contextClosing() {
        close();
    }

    @PreDestroy
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        synchronized (lifecycleLock) {
            if (closing) return;
            closing = true;
            LoggerFactory.getLogger(VisitMqRuntime.class).info("Application lifecycle: operation=lifecycle result=stopping");
            recorder.stopAccepting();
            startup.shutdownNow();
        }
        var consumerClosed = new CountDownLatch(1);
        listener.stop(consumerClosed::countDown);
        recorder.close(deadline);
        // Every wait spends the same deadline; timeout is not a process-wide shutdown guarantee.
        try {
            long left = deadline - System.nanoTime();
            if (left > 0) startup.awaitTermination(left, TimeUnit.NANOSECONDS);
            left = deadline - System.nanoTime();
            if (left > 0) consumerClosed.await(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void startupLoop() {
        while (!closing) {
            try {
                admin.initialize();
                // The listener adapter also rejects start once terminal stop has claimed ownership.
                if (!closing && properties.consumerEnabled()) listener.start();
                com.example.shortlink.logging.SafeOperationalLog.recovered(LoggerFactory.getLogger(VisitMqRuntime.class),
                        com.example.shortlink.logging.SafeOperationalLog.Category.MQ_STARTUP);
                return;
            } catch (Exception failure) {
                com.example.shortlink.logging.SafeOperationalLog.sampled(LoggerFactory.getLogger(VisitMqRuntime.class),
                        com.example.shortlink.logging.SafeOperationalLog.Category.MQ_STARTUP);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
