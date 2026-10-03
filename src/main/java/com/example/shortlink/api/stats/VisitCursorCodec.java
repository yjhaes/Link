package com.example.shortlink.api.stats;

import com.example.shortlink.service.error.InvalidRequestException;
import com.example.shortlink.stats.StatsDateRange;
import com.example.shortlink.stats.query.VisitCursor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/** A versioned query position, never an authorization or snapshot credential. */
final class VisitCursorCodec {
    private VisitCursorCodec() {}

    static String encode(VisitCursor cursor, String code, StatsDateRange range) {
        String value =
                "1|"
                        + code
                        + "|"
                        + range.from()
                        + "|"
                        + range.to()
                        + "|"
                        + cursor.occurredAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                        + "|"
                        + cursor.id();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    static VisitCursor parse(String value, String code, StatsDateRange range) {
        try {
            if (value == null || value.length() > 200 || !value.matches("[A-Za-z0-9_-]+"))
                throw invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))
                throw invalid();
            String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\\|", -1);
            if (fields.length != 6
                    || !fields[0].equals("1")
                    || !fields[1].equals(code)
                    || !fields[2].equals(range.from().toString())
                    || !fields[3].equals(range.to().toString())
                    || !fields[4].matches("-?(0|[1-9][0-9]{0,15})")
                    || !fields[5].matches("[1-9][0-9]{0,18}")) throw invalid();
            var position =
                    LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(Long.parseLong(fields[4])), ZoneOffset.UTC);
            long id = Long.parseLong(fields[5]);
            if (position.isBefore(range.startUtc())
                    || !position.isBefore(range.endUtc())
                    || id <= 0) throw invalid();
            var cursor = new VisitCursor(position, id);
            if (!encode(cursor, code, range).equals(value)) throw invalid();
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(
                "Cursor must be a valid position for this code and date range.");
    }
}
