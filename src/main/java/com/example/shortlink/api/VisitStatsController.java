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

    public VisitStatsController(MySqlVisitStatsQuery query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    @GetMapping("/api/internal/links/{code}/stats")
    public ResponseEntity<VisitStatsResponse> statistics(@PathVariable String code,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (!code.matches("[A-Za-z0-9]{4,8}")) throw new LinkNotFoundException();
        var range = StatsDateRange.parse(parameters, clock);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.query(code, range));
    }
}
