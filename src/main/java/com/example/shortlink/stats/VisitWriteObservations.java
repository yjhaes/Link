package com.example.shortlink.stats;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import javax.sql.DataSource;

/** Internal, process-local observations. Snapshots are approximate during concurrent writes. */
@Component
public final class VisitWriteObservations {
    public enum Outcome {
        SAVED,
        DUPLICATE,
        DROPPED,
        FAILED,
        UNCERTAIN
    }

    public enum Category {
        CONNECTION,
        TIMEOUT,
        CONSTRAINT,
        DATABASE,
        UNEXPECTED,
        COLLECTION,
        CLEANUP
    }

    private final DataSource pool;
    private final LongAdder attempted = new LongAdder();
    private final LongAdder duration = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<Outcome, LongAdder> outcomes = counters(Outcome.class);
    private final Map<Category, LongAdder> categories = counters(Category.class);

    public VisitWriteObservations(@Qualifier("statsDataSource") DataSource pool) {
        this.pool = pool;
    }

    void attempted() {
        attempted.increment();
    }

    void started() {
        inFlight.incrementAndGet();
    }

    void finished() {
        inFlight.decrementAndGet();
    }

    void outcome(Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    void category(Category category) {
        categories.get(category).increment();
    }

    void duration(long nanos) {
        duration.add(nanos);
    }

    public void collectionFailed() {
        category(Category.COLLECTION);
    }

    public Snapshot snapshot() {
        var bean = pool instanceof HikariDataSource hikari ? hikari.getHikariPoolMXBean() : null;
        return new Snapshot(
                attempted.sum(),
                values(outcomes),
                values(categories),
                duration.sum(),
                inFlight.get(),
                bean == null ? 0 : bean.getActiveConnections(),
                bean == null ? 0 : bean.getIdleConnections(),
                bean == null ? 0 : bean.getTotalConnections(),
                bean == null ? 0 : bean.getThreadsAwaitingConnection());
    }

    private static <E extends Enum<E>> Map<E, LongAdder> counters(Class<E> type) {
        var result = new EnumMap<E, LongAdder>(type);
        for (E key : type.getEnumConstants()) result.put(key, new LongAdder());
        return result;
    }

    private static <E extends Enum<E>> Map<E, Long> values(Map<E, LongAdder> counters) {
        var result = new EnumMap<E, Long>(counters.keySet().iterator().next().getDeclaringClass());
        counters.forEach((key, value) -> result.put(key, value.sum()));
        return Map.copyOf(result);
    }

    public record Snapshot(
            long attempted,
            Map<Outcome, Long> outcomes,
            Map<Category, Long> categories,
            long durationNanos,
            int inFlight,
            int poolActive,
            int poolIdle,
            int poolTotal,
            int poolWaiting) {}
}
