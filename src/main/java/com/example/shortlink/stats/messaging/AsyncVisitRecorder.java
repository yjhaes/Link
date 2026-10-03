package com.example.shortlink.stats.messaging;


import com.example.shortlink.stats.VisitEvent;
import com.example.shortlink.stats.VisitRecorder;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** HTTP admission never waits for MQ; observation and cleanup cannot depend on the sender. */
@Component
@Primary
public class AsyncVisitRecorder implements VisitRecorder {
    private static final long BUDGET = TimeUnit.SECONDS.toNanos(5);

    private record Pending(VisitEvent event, long acceptedAt) {}

    private final class Attempt {
        final CorrelationData correlation;
        final long began = System.nanoTime();
        final AtomicBoolean terminal = new AtomicBoolean(), released = new AtomicBoolean();

        Attempt() {
            correlation = new CorrelationData(UUID.randomUUID().toString());
        }

        void finish(Outcome result) {
            if (terminal.compareAndSet(false, true)) {
                count(result);
                publishDurationNanos.add(System.nanoTime() - began);
            }
        }

        void retire() {
            if (released.compareAndSet(false, true)) {
                attempts.remove(this);
                permits.release();
            }
        }
    }

    private final ArrayBlockingQueue<Pending> pending;
    private final Semaphore permits;
    private final Set<Attempt> attempts = ConcurrentHashMap.newKeySet();
    private final LongAdder publishDurationNanos = new LongAdder();

    private enum OutcomeLayer {
        EVENT,
        PUBLISH
    }

    private enum Outcome {
        LOCAL_ACCEPTED(OutcomeLayer.EVENT, "local-accepted"),
        SHUTDOWN_LOST(OutcomeLayer.EVENT, "shutdown-lost"),
        FULL(OutcomeLayer.EVENT, "full"),
        EXPIRED(OutcomeLayer.EVENT, "expired"),
        LIMITED(OutcomeLayer.EVENT, "limited"),
        ENCODING(OutcomeLayer.EVENT, "encoding"),
        ATTEMPT(OutcomeLayer.PUBLISH, "attempt"),
        ACCEPTED(OutcomeLayer.PUBLISH, "accepted"),
        RETURN(OutcomeLayer.PUBLISH, "return"),
        NACK(OutcomeLayer.PUBLISH, "nack"),
        UNKNOWN(OutcomeLayer.PUBLISH, "unknown"),
        SEND_FAILED(OutcomeLayer.PUBLISH, "send-failed"),
        RECOVERY(OutcomeLayer.PUBLISH, "recovery"),
        RECOVERY_FAILED(OutcomeLayer.PUBLISH, "recovery-failed");
        final OutcomeLayer layer;
        final String label;

        Outcome(OutcomeLayer layer, String label) {
            this.layer = layer;
            this.label = label;
        }
    }

    private final Map<Outcome, LongAdder> outcomes = new EnumMap<>(Outcome.class);
    private final RabbitTemplate template;
    private final CachingConnectionFactory publisher;
    private final VisitMessageCodec codec;
    private final VisitRabbitProperties properties;
    private final AtomicBoolean accepting = new AtomicBoolean(true),
            started = new AtomicBoolean(),
            recovering = new AtomicBoolean();
    private final Object recoveryLock = new Object();
    private final Object admissionLock = new Object();
    private final AtomicBoolean senderBusy = new AtomicBoolean(), cleanupDone = new AtomicBoolean();
    private final ExecutorService sender =
            Executors.newSingleThreadExecutor(r -> daemon(r, "visit-publisher"));
    private final ExecutorService cleanup =
            Executors.newSingleThreadExecutor(r -> daemon(r, "visit-publisher-cleanup"));
    private final ScheduledExecutorService observer =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "visit-publisher-observer"));

    private static Thread daemon(Runnable r, String name) {
        var t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public AsyncVisitRecorder(
            VisitRabbitProperties properties,
            @Qualifier("visitRabbitTemplate") RabbitTemplate template,
            VisitMessageCodec codec,
            @Qualifier("visitPublisherConnectionFactory") CachingConnectionFactory publisher) {
        this.properties = properties;
        this.template = template;
        this.codec = codec;
        this.publisher = publisher;
        pending = new ArrayBlockingQueue<>(properties.bufferCapacity());
        permits = new Semaphore(properties.unconfirmedLimit());
        for (Outcome category : Outcome.values()) outcomes.put(category, new LongAdder());
    }

    public record Snapshot(
            int pending,
            int unconfirmed,
            boolean recovering,
            Map<String, Long> outcomes,
            Map<String, Long> eventOutcomes,
            Map<String, Long> publishOutcomes,
            long localOldestQueuedAgeNanos,
            long publishDurationNanos) {}

    public Snapshot snapshot() {
        var counts = new HashMap<String, Long>();
        var events = new HashMap<String, Long>();
        var publishes = new HashMap<String, Long>();
        outcomes.forEach(
                (k, v) -> {
                    long value = v.sum();
                    counts.put(k.label, value);
                    if (k.layer == OutcomeLayer.EVENT) events.put(k.label, value);
                    else publishes.put(k.label, value);
                });
        var oldest = pending.peek();
        long age = oldest == null ? 0 : Math.max(0, System.nanoTime() - oldest.acceptedAt());
        return new Snapshot(
                pending.size(),
                properties.unconfirmedLimit() - permits.availablePermits(),
                recovering.get(),
                Map.copyOf(counts),
                Map.copyOf(events),
                Map.copyOf(publishes),
                age,
                publishDurationNanos.sum());
    }

    private void count(Outcome category) {
        outcomes.get(category).increment();
    }

    @Override
    public void record(VisitEvent event) {
        synchronized (admissionLock) {
            if (accepting.get())
                count(
                        pending.offer(new Pending(event, System.nanoTime()))
                                ? Outcome.LOCAL_ACCEPTED
                                : Outcome.FULL);
        }
    }

    void start() {
        synchronized (admissionLock) {
            if (!started.compareAndSet(false, true) || !accepting.get()) return;
            sender.execute(this::sendLoop);
            observer.scheduleWithFixedDelay(this::observe, 100, 100, TimeUnit.MILLISECONDS);
        }
    }

    private void sendLoop() {
        while (accepting.get()) {
            try {
                if (recovering.get()) {
                    TimeUnit.MILLISECONDS.sleep(50);
                    continue;
                }
                Pending next = pending.poll(100, TimeUnit.MILLISECONDS);
                if (next == null) continue;
                if (System.nanoTime() - next.acceptedAt() > BUDGET) {
                    count(Outcome.EXPIRED);
                    continue;
                }
                if (!permits.tryAcquire()) {
                    count(Outcome.LIMITED);
                    continue;
                }
                byte[] body;
                try {
                    body = codec.encode(next.event());
                } catch (Exception invalid) {
                    permits.release();
                    count(Outcome.ENCODING);
                    continue;
                }
                var attempt = new Attempt();
                synchronized (admissionLock) {
                    if (!accepting.get()) {
                        permits.release();
                        count(Outcome.SHUTDOWN_LOST);
                        continue;
                    }
                    attempts.add(attempt);
                    senderBusy.set(true);
                }
                try {
                    // Recheck after admission: a concurrent timeout can have closed the recovery
                    // gate.
                    if (!accepting.get() || recovering.get()) {
                        attempt.finish(Outcome.UNKNOWN);
                        attempt.retire();
                        continue;
                    }
                    attempt.correlation
                            .getFuture()
                            .whenComplete(
                                    (confirm, failure) -> {
                                        if (attempt.correlation.getReturned() != null)
                                            attempt.finish(Outcome.RETURN);
                                        else if (failure != null) attempt.finish(Outcome.UNKNOWN);
                                        else
                                            attempt.finish(
                                                    confirm.isAck()
                                                            ? Outcome.ACCEPTED
                                                            : Outcome.NACK);
                                        attempt.retire();
                                    });
                    var mp = new MessageProperties();
                    mp.setContentType("application/json");
                    mp.setContentEncoding("UTF-8");
                    mp.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    if (System.nanoTime() - next.acceptedAt() > BUDGET) {
                        attempt.finish(Outcome.EXPIRED);
                        attempt.retire();
                        continue;
                    }
                    count(Outcome.ATTEMPT);
                    template.send(
                            VisitRabbitConfiguration.EXCHANGE,
                            VisitRabbitConfiguration.KEY,
                            new Message(body, mp),
                            attempt.correlation);
                } catch (Exception failure) {
                    attempt.finish(
                            attempt.correlation.getReturned() != null
                                    ? Outcome.RETURN
                                    : Outcome.SEND_FAILED);
                    attempt.retire();
                    requestRecovery();
                } finally {
                    senderBusy.set(false);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                log("publisher");
            }
        }
    }

    private void observe() {
        try {
            boolean timedOut = false;
            long now = System.nanoTime();
            for (var attempt : attempts) {
                if (attempt.correlation.getReturned() != null) attempt.finish(Outcome.RETURN);
                if (now - attempt.began > BUDGET) {
                    attempt.finish(Outcome.UNKNOWN);
                    attempt.retire();
                    timedOut = true;
                }
            }
            if (timedOut) requestRecovery();
            // A completed reset is insufficient until the original sender actually returns.
            synchronized (recoveryLock) {
                if (recovering.get() && cleanupDone.get() && !senderBusy.get())
                    recovering.set(false);
            }
        } catch (Exception failure) {
            log("observer");
        }
    }

    private void requestRecovery() {
        synchronized (recoveryLock) {
            if (!accepting.get() || recovering.get()) return;
            cleanupDone.set(false);
            recovering.set(true);
        }
        count(Outcome.RECOVERY);
        for (var attempt : attempts) {
            attempt.finish(Outcome.UNKNOWN);
            attempt.retire();
        }
        try {
            cleanup.execute(
                    () -> {
                        try {
                            template.getUnconfirmed(0);
                            publisher.resetConnection();
                            cleanupDone.set(true);
                        } catch (Exception failure) {
                            count(Outcome.RECOVERY_FAILED);
                            log("recovery");
                        }
                    });
        } catch (RejectedExecutionException stopped) {
            /* shutdown retains the gate */
        }
    }

    private void log(String category) {
        org.slf4j.LoggerFactory.getLogger(AsyncVisitRecorder.class)
                .warn("Visit MQ degraded: category={}", category);
    }

    private final AtomicBoolean closed = new AtomicBoolean();

    void stopAccepting() {
        synchronized (admissionLock) {
            if (!accepting.getAndSet(false)) return;
            for (int i = pending.size(); i > 0; i--) count(Outcome.SHUTDOWN_LOST);
            pending.clear();
        }
    }

    void close(long deadline) {
        if (!closed.compareAndSet(false, true)) return;
        stopAccepting();
        observer.shutdownNow();
        for (var attempt : attempts) {
            attempt.finish(Outcome.UNKNOWN);
            attempt.retire();
        }
        sender.shutdownNow();
        // Never destroy the CCF synchronously: reset can block behind a TCP write.
        recovering.set(true);
        try {
            cleanup.execute(
                    () -> {
                        try {
                            publisher.destroy();
                        } catch (Exception failure) {
                            log("close");
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            // A repeated close cannot create a replacement cleanup worker.
        }
        cleanup.shutdown();
        try {
            for (var worker : List.of(sender, observer, cleanup)) {
                long left = deadline - System.nanoTime();
                if (left > 0) worker.awaitTermination(left, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
