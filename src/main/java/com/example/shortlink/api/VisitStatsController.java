package com.example.shortlink.api;

import com.example.shortlink.service.error.InvalidRequestException;
import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.stats.MySqlVisitStatsQuery;
import com.example.shortlink.stats.StatsDateRange;
import com.example.shortlink.stats.VisitStatsProperties;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneOffset;

@RestController
@InternalManagement
public class VisitStatsController {
    private final MySqlVisitStatsQuery query;
    private final Clock clock;
    private final VisitStatsProperties properties;

    public VisitStatsController(
            MySqlVisitStatsQuery query, Clock clock, VisitStatsProperties properties) {
        this.query = query;
        this.clock = clock;
        this.properties = properties;
    }

    @GetMapping("/api/internal/links/{code}/stats")
    public ResponseEntity<VisitStatsResponse> statistics(
            @PathVariable String code, @RequestParam MultiValueMap<String, String> parameters) {
        if (!code.matches("[A-Za-z0-9]{4,8}")) throw new LinkNotFoundException();
        var range = StatsDateParameters.parse(parameters, clock);
        var result = query.query(code, range);
        var response =
                new VisitStatsResponse(
                        code,
                        range.from(),
                        range.to(),
                        StatsDateRange.ZONE.getId(),
                        result.pv(),
                        result.uv(),
                        "anonymous-cookie",
                        "best-effort",
                        properties.enabled(),
                        result.identityVersions(),
                        clock.instant(),
                        result.daily().stream()
                                .map(
                                        day ->
                                                new VisitStatsResponse.Day(
                                                        day.date(),
                                                        day.pv(),
                                                        day.uv(),
                                                        day.isOngoing()))
                                .toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/api/internal/links/{code}/visits")
    public ResponseEntity<VisitPageResponse> visits(
            @PathVariable String code, @RequestParam MultiValueMap<String, String> parameters) {
        if (!code.matches("[A-Za-z0-9]{4,8}")) throw new LinkNotFoundException();
        var range = StatsDateParameters.parse(parameters, clock);
        int limit = 20;
        if (parameters.containsKey("limit")) {
            String value = single(parameters, "limit");
            if (!value.matches("[0-9]{1,3}")) throw invalidPage();
            limit = Integer.parseInt(value);
            if (limit < 1 || limit > 100) throw invalidPage();
        }
        var cursor =
                parameters.containsKey("cursor")
                        ? VisitCursorCodec.parse(single(parameters, "cursor"), code, range)
                        : null;
        var result = query.page(code, range, limit, cursor);
        var items =
                result.items().stream()
                        .map(
                                row ->
                                        new VisitPageResponse.Item(
                                                row.occurredAt().toInstant(ZoneOffset.UTC),
                                                row.peerIpNetwork(),
                                                row.userAgent(),
                                                row.refererHost()))
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new VisitPageResponse(
                                code,
                                range.from(),
                                range.to(),
                                items,
                                result.nextPosition() == null
                                        ? null
                                        : VisitCursorCodec.encode(
                                                result.nextPosition(), code, range),
                                result.hasMore()));
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        var values = parameters.get(name);
        if (values == null || values.size() != 1 || values.get(0) == null) throw invalidPage();
        return values.get(0);
    }

    private static InvalidRequestException invalidPage() {
        return new InvalidRequestException(
                "Page parameters must occur once; limit must be an integer from 1 to 100.");
    }
}
