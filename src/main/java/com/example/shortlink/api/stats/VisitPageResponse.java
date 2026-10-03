package com.example.shortlink.api.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record VisitPageResponse(
        String shortCode,
        LocalDate from,
        LocalDate to,
        List<Item> items,
        String nextCursor,
        boolean hasMore) {
    public record Item(
            Instant occurredAt, String peerIpNetwork, String userAgent, String refererHost) {}
}
