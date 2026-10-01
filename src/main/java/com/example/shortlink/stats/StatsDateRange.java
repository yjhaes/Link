package com.example.shortlink.stats;

import com.example.shortlink.service.error.InvalidRequestException;
import org.springframework.util.MultiValueMap;
import java.time.*;
import java.time.format.DateTimeParseException;

/** One request's fixed Shanghai calendar window, shared by statistics queries. */
public record StatsDateRange(LocalDate from, LocalDate to, LocalDate today) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static StatsDateRange parse(MultiValueMap<String, String> parameters, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        if (!parameters.containsKey("from") && !parameters.containsKey("to")) {
            return new StatsDateRange(today.minusDays(6), today, today);
        }
        LocalDate from = date(parameters, "from");
        LocalDate to = date(parameters, "to");
        if (from.isAfter(to) || to.isAfter(today) || from.isBefore(today.minusDays(29))) throw invalid();
        return new StatsDateRange(from, to, today);
    }

    private static LocalDate date(MultiValueMap<String, String> parameters, String key) {
        var values = parameters.get(key);
        if (values == null || values.size() != 1 || values.get(0) == null
                || !values.get(0).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try { return LocalDate.parse(values.get(0)); }
        catch (DateTimeParseException failure) { throw invalid(); }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException("Dates must specify an inclusive range within the last 30 Shanghai days.");
    }

    public LocalDateTime startUtc() {
        return LocalDateTime.ofInstant(from.atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
    }

    public LocalDateTime endUtc() {
        return LocalDateTime.ofInstant(to.plusDays(1).atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
    }
}
