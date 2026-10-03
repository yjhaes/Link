package com.example.shortlink.stats.query;

import java.time.LocalDateTime;
import java.util.List;

public record VisitPageResult(List<Row> items, VisitCursor nextPosition, boolean hasMore) {
    public record Row(
            long id,
            LocalDateTime occurredAt,
            String peerIpNetwork,
            String userAgent,
            String refererHost) {}
}
