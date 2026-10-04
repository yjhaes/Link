package com.example.shortlink.logging;

import org.slf4j.Logger;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;

/** Fixed categories, bounded process-local samples; this changes observation, never admission/retry. */
public final class SafeOperationalLog {
    public enum Category {
        CACHE_READ, CACHE_WRITE, CACHE_MALFORMED, CACHE_CLEANUP, STATE_RETRY, GENERATION,
        MQ_PUBLISH, MQ_RECOVERY, MQ_STARTUP, MQ_CONSUMER, COLLECTION, STAT_WRITE, STAT_CLEANUP, CLEANUP,
        RATE_CREATE, RATE_REDIRECT, RATE_MANAGEMENT_WRITE, RATE_MANAGEMENT_QUERY
    }
    private static final EnumMap<Category, Long> last = new EnumMap<>(Category.class);
    private static final EnumMap<Category, Long> counts = new EnumMap<>(Category.class);
    private static final java.util.EnumSet<Category> degraded = java.util.EnumSet.noneOf(Category.class);
    private static final EnumMap<Category, Long> lastRecovery = new EnumMap<>(Category.class);
    private SafeOperationalLog() {}
    public static synchronized void recovered(Logger logger, Category category) {
        if (!degraded.remove(category)) return;
        long now = System.nanoTime();
        Long previous = lastRecovery.get(category);
        if (previous == null || now - previous >= TimeUnit.SECONDS.toNanos(30)) {
            logger.info("Dependency recovery: category={} sampleWindowSeconds=30",
                    category.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            lastRecovery.put(category, now);
        }
    }
    public static void sampled(Logger logger, Category category) {
        sampled(logger, category, null, null);
    }
    /** Enum details are fixed vocabularies, never an exception message or request metadata. */
    public static synchronized void sampled(Logger logger, Category category, Enum<?> detail, Enum<?> phase) {
        degraded.add(category);
        long now = System.nanoTime();
        long count = counts.merge(category, 1L, Long::sum);
        Long previous = last.get(category);
        if (previous == null || now - previous >= TimeUnit.SECONDS.toNanos(30)) {
            String operation = category.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            if (detail == null) logger.warn("Operational failure: category={} observedCount={} sampleWindowSeconds=30", operation, count);
            else logger.warn("Operational failure: operation={} category={} phase={} observedCount={} sampleWindowSeconds=30",
                    operation, detail, phase, count);
            last.put(category, now);
        }
    }
}
