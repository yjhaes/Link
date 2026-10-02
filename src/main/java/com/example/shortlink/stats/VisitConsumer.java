package com.example.shortlink.stats;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

/** One synchronous delivery: only a confirmed persistence outcome permits AUTO acknowledgment. */
public final class VisitConsumer implements MessageListener {
    private final VisitMessageCodec codec;
    private final VisitPersistence persistence;
    public VisitConsumer(VisitMessageCodec codec, VisitPersistence persistence) {
        this.codec = codec;
        this.persistence = persistence;
    }
    @Override public void onMessage(Message message) {
        final VisitEvent event;
        try { event = codec.decode(message.getBody()); }
        catch (RuntimeException invalid) { throw rejected(); }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                VisitPersistence.Outcome outcome = persistence.persist(event);
                if (outcome == VisitPersistence.Outcome.SAVED || outcome == VisitPersistence.Outcome.DUPLICATE) return;
                throw rejected();
            } catch (RuntimeException failure) {
                var kind = controlledFailure(failure);
                if (kind == null || kind == VisitPersistenceException.Failure.PERMANENT || attempt == 2) throw rejected();
                try { Thread.sleep(attempt == 0 ? 200 : 500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw rejected(); }
            }
        }
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
