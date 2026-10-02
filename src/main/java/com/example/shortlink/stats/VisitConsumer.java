package com.example.shortlink.stats;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

/** Synchronous delivery: confirmed persistence or a legal expired event permits AUTO acknowledgment. */
public final class VisitConsumer implements MessageListener {
    public enum Outcome { SAVED, DUPLICATE, EXPIRED }
    private final java.time.Clock clock;
    private final java.util.concurrent.atomic.LongAdder expired = new java.util.concurrent.atomic.LongAdder();
    public long expiredCount() { return expired.sum(); }
    private final VisitMessageCodec codec;
    private final VisitPersistence persistence;
    public VisitConsumer(VisitMessageCodec codec, VisitPersistence persistence, java.time.Clock clock) {
        this.clock = clock;
        this.codec = codec;
        this.persistence = persistence;
    }
    @Override public void onMessage(Message message) { consume(message); }
    public Outcome consume(Message message) {
        final VisitEvent event;
        try { event = codec.decode(message.getBody()); }
        catch (RuntimeException invalid) { throw rejected(); }
        for (int attempt = 0; attempt < 3; attempt++) {
            var today = java.time.LocalDate.now(clock.withZone(StatsDateRange.ZONE));
            if (event.statDate().isAfter(today)) throw rejected();
            if (event.statDate().isBefore(StatsDateRange.earliestRetainedDate(today))) {
                expired.increment();
                return Outcome.EXPIRED;
            }
            try {
                VisitPersistence.Outcome outcome = persistence.persist(event);
                if (outcome == VisitPersistence.Outcome.SAVED) return Outcome.SAVED;
                if (outcome == VisitPersistence.Outcome.DUPLICATE) return Outcome.DUPLICATE;
                throw rejected();
            } catch (RuntimeException failure) {
                var kind = controlledFailure(failure);
                if (kind == null || kind == VisitPersistenceException.Failure.PERMANENT || attempt == 2) throw rejected();
                try { Thread.sleep(attempt == 0 ? 200 : 500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw rejected(); }
            }
        }
        throw rejected();
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
