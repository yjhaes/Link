package com.example.shortlink.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Explicit driver namespaces only; not a general-purpose secret scrubber. */
public final class SafeDependencyConsoleEncoder extends PatternLayoutEncoder {
    private final Map<String, Long> last = new HashMap<>();
    private final Map<String, Long> counts = new HashMap<>();
    @Override public synchronized byte[] encode(ILoggingEvent event) {
        String name = event.getLoggerName();
        String category = name.startsWith("com.zaxxer.hikari") || name.startsWith("com.mysql.cj")
                || name.startsWith("org.springframework.jdbc") ? "database"
                : name.startsWith("org.springframework.amqp") || name.startsWith("com.rabbitmq.client")
                || name.equals("com.example.shortlink.stats.messaging.VisitConnectionFactory")
                || name.equals("com.example.shortlink.stats.messaging.VisitListenerContainer") ? "mq"
                : name.startsWith("io.lettuce.core") || name.startsWith("org.springframework.data.redis") ? "redis"
                : name.startsWith("com.example.shortlink.") && event.getThrowableProxy() != null ? "application" : null;
        if (category == null) return super.encode(event);
        String key = category + ":" + event.getLevel().toInt();
        long count = counts.merge(key, 1L, Long::sum);
        long now = System.nanoTime();
        Long previous = last.get(key);
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(30)) return new byte[0];
        last.put(key, now);
        var safe = new LoggingEvent(null, ((LoggerContext) getContext()).getLogger(name), event.getLevel(),
                "Dependency event: category=" + category + " observedCount=" + count + " sampleWindowSeconds=30", null, null);
        safe.setThreadName(event.getThreadName());
        safe.setTimeStamp(event.getTimeStamp());
        // The app supplies only a server-generated requestId; no request metadata enters MDC.
        safe.setMDCPropertyMap(event.getMDCPropertyMap());
        return super.encode(safe);
    }
}
