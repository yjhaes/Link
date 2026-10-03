package com.example.shortlink.stats;

import com.example.shortlink.service.error.InvalidRequestException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** One request's fixed Shanghai calendar window, shared by statistics queries. */
public record StatsDateRange(LocalDate from, LocalDate to, LocalDate today) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public StatsDateRange {
        if (from.isAfter(to) || to.isAfter(today) || from.isBefore(earliestRetainedDate(today)))
            throw invalid();
    }

    public static LocalDate earliestRetainedDate(LocalDate today) {
        return today.minusDays(29);
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(
                "Dates must specify an inclusive range within the last 30 Shanghai days.");
    }

    public LocalDateTime startUtc() {
        return LocalDateTime.ofInstant(from.atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
    }

    public LocalDateTime endUtc() {
        return LocalDateTime.ofInstant(
                to.plusDays(1).atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
    }
}
