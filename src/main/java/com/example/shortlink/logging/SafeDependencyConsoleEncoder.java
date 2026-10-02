package com.example.shortlink.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.LoggerContext;

/** Keep each dependency event and its level/context, replacing unsafe driver or transport text. */
public final class SafeDependencyConsoleEncoder extends PatternLayoutEncoder {
    @Override public byte[] encode(ILoggingEvent event) {
        String name = event.getLoggerName();
        String category = name.startsWith("com.zaxxer.hikari") || name.startsWith("com.mysql.cj")
                || name.startsWith("org.springframework.jdbc") ? "database"
                : name.startsWith("org.springframework.amqp") || name.startsWith("com.rabbitmq.client") || name.equals("com.example.shortlink.stats.VisitConnectionFactory")
                || name.equals("com.example.shortlink.stats.VisitListenerContainer")
                ? "mq" : null;
        if (category == null) return super.encode(event);
        var safe = new LoggingEvent(null, ((LoggerContext) getContext()).getLogger(name), event.getLevel(),
                "Dependency event: category=" + category, null, null);
        safe.setThreadName(event.getThreadName());
        safe.setTimeStamp(event.getTimeStamp());
        safe.setMDCPropertyMap(event.getMDCPropertyMap());
        return super.encode(safe);
    }
}

