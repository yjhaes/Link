package com.example.shortlink.api;

import com.example.shortlink.service.error.InvalidRequestException;
import com.example.shortlink.stats.StatsDateRange;

import org.springframework.util.MultiValueMap;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Strict HTTP date parsing before entering the statistics query boundary. */
final class StatsDateParameters {
    private StatsDateParameters() {}

    static StatsDateRange parse(MultiValueMap<String, String> parameters, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(StatsDateRange.ZONE));
        if (!parameters.containsKey("from") && !parameters.containsKey("to")) {
            return new StatsDateRange(today.minusDays(6), today, today);
        }
        return new StatsDateRange(date(parameters, "from"), date(parameters, "to"), today);
    }

    private static LocalDate date(MultiValueMap<String, String> parameters, String key) {
        var values = parameters.get(key);
        if (values == null
                || values.size() != 1
                || values.get(0) == null
                || !values.get(0).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try {
            return LocalDate.parse(values.get(0));
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(
                "Dates must use YYYY-MM-DD and specify both from and to once.");
    }
}
