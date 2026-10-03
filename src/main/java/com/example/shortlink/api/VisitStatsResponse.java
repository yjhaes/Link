package com.example.shortlink.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record VisitStatsResponse(
        String shortCode,
        LocalDate from,
        LocalDate to,
        String timeZone,
        long pv,
        long uv,
        String uvBasis,
        String collectionPolicy,
        boolean collectionEnabled,
        List<Integer> identityVersions,
        Instant generatedAt,
        List<Day> daily) {
    public record Day(LocalDate date, long pv, long uv, boolean isOngoing) {}
}
