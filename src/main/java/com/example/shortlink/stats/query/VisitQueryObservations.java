package com.example.shortlink.stats.query;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/** Process-local query timeout count, without request or identity labels. */
@Component
public final class VisitQueryObservations {
    private final LongAdder timeouts = new LongAdder();

    void timedOut() {
        timeouts.increment();
    }

    public long timeouts() {
        return timeouts.sum();
    }
}
