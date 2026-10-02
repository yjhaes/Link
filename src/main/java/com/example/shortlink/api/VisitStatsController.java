package com.example.shortlink.api;

import com.example.shortlink.service.error.LinkNotFoundException;
import com.example.shortlink.stats.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;

@RestController
@InternalManagement
public class VisitStatsController {
    private final MySqlVisitStatsQuery query;
    private final Clock clock;
    private final VisitStatsProperties properties;

    public VisitStatsController(MySqlVisitStatsQuery query, Clock clock, VisitStatsProperties properties) {
        this.query = query;
        this.clock = clock;
        this.properties = properties;
    }

    @GetMapping("/api/internal/links/{code}/stats")
    public ResponseEntity<VisitStatsResponse> statistics(@PathVariable String code,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (!code.matches("[A-Za-z0-9]{4,8}")) throw new LinkNotFoundException();
        var range = StatsDateParameters.parse(parameters, clock);
        var result = query.query(code, range);
        var response = new VisitStatsResponse(code, range.from(), range.to(), StatsDateRange.ZONE.getId(),
                result.pv(), result.uv(), "anonymous-cookie", "best-effort", properties.enabled(),
                result.identityVersions(), clock.instant(), result.daily().stream()
                        .map(day -> new VisitStatsResponse.Day(day.date(), day.pv(), day.uv(), day.isOngoing())).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
