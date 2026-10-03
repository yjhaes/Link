package com.example.shortlink.stats;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

/** Synchronous delivery: confirmed persistence or a legal expired event permits AUTO acknowledgment. */
public final class VisitConsumer implements MessageListener {
    public enum Outcome { SAVED, DUPLICATE, EXPIRED }
    public enum Category { SAVED, DUPLICATE, EXPIRED, INVALID, ATTEMPT_FAILED, EXHAUSTED, PERMANENT, INTERRUPTED }
    private final long observedSince = System.nanoTime();
    private final java.util.concurrent.atomic.LongAdder deliveries = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder persistenceAttempts = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder processingNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder completed = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder processedEventDelayMillis = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder processedEventDelayCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Map<Category, java.util.concurrent.atomic.LongAdder> categories = new java.util.EnumMap<>(Category.class);
    public record Snapshot(long deliveries, long persistenceAttempts, java.util.Map<Category, Long> outcomes,
            long processingNanos, int inFlight, double completedPerSecond, long processedEventDelayMillis,
            long processedEventDelayCount) { }
    public Snapshot snapshot() {
        var values = new java.util.EnumMap<Category, Long>(Category.class);
        categories.forEach((key, value) -> values.put(key, value.sum()));
        return new Snapshot(deliveries.sum(), persistenceAttempts.sum(), java.util.Map.copyOf(values),
                processingNanos.sum(), inFlight.get(), completed.sum() * 1_000_000_000d / Math.max(1, System.nanoTime() - observedSince),
                processedEventDelayMillis.sum(), processedEventDelayCount.sum());
    }
    private void count(Category category) { categories.get(category).increment(); }
    private final java.time.Clock clock;
    private final java.util.concurrent.atomic.LongAdder expired = new java.util.concurrent.atomic.LongAdder();
    public long expiredCount() { return expired.sum(); }
    private final VisitMessageCodec codec;
    private final VisitPersistence persistence;
    public VisitConsumer(VisitMessageCodec codec, VisitPersistence persistence, java.time.Clock clock) {
        for (var category : Category.values()) categories.put(category, new java.util.concurrent.atomic.LongAdder());
        this.clock = clock;
        this.codec = codec;
        this.persistence = persistence;
    }
    @Override public void onMessage(Message message) { consume(message); }
    public Outcome consume(Message message) {
        long began = System.nanoTime();
        deliveries.increment(); inFlight.incrementAndGet();
        try { return process(message); }
        finally { processingNanos.add(System.nanoTime() - began); inFlight.decrementAndGet(); completed.increment(); }
    }
    private Outcome process(Message message) {
        final VisitEvent event;
        try { event = codec.decode(message.getBody()); }
        catch (RuntimeException invalid) { count(Category.INVALID); throw rejected(); }
        for (int attempt = 0; attempt < 3; attempt++) {
            var today = java.time.LocalDate.now(clock.withZone(StatsDateRange.ZONE));
            if (event.statDate().isAfter(today)) { count(Category.INVALID); throw rejected(); }
            if (event.statDate().isBefore(StatsDateRange.earliestRetainedDate(today))) {
                expired.increment(); count(Category.EXPIRED);
                return Outcome.EXPIRED;
            }
            try {
                persistenceAttempts.increment();
                VisitPersistence.Outcome outcome = persistence.persist(event);
                if (outcome == VisitPersistence.Outcome.SAVED) { processed(event); count(Category.SAVED); return Outcome.SAVED; }
                if (outcome == VisitPersistence.Outcome.DUPLICATE) { processed(event); count(Category.DUPLICATE); return Outcome.DUPLICATE; }
                throw rejected();
            } catch (RuntimeException failure) {
                count(Category.ATTEMPT_FAILED);
                var kind = controlledFailure(failure);
                if (kind == null || kind == VisitPersistenceException.Failure.PERMANENT) { count(Category.PERMANENT); throw rejected(); }
                if (attempt == 2) { count(Category.EXHAUSTED); throw rejected(); }
                try { Thread.sleep(attempt == 0 ? 200 : 500); }
                catch (InterruptedException interrupted) { count(Category.INTERRUPTED); Thread.currentThread().interrupt(); throw rejected(); }
            }
        }
        throw rejected();
    }
    private void processed(VisitEvent event) {
        // Completed events only; this is neither broker oldest age nor a completeness watermark.
        processedEventDelayMillis.add(Math.max(0, java.time.Duration.between(event.occurredAt(), clock.instant()).toMillis()));
        processedEventDelayCount.increment();
    }
    private VisitPersistenceException.Failure controlledFailure(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof VisitPersistenceException controlled) return controlled.failure();
        }
        return null;
    }
    private AmqpRejectAndDontRequeueException rejected() {
        // No cause: the framework must never receive payloads, driver messages or credentials.
        return new AmqpRejectAndDontRequeueException("Visit delivery rejected.");
    }
}
